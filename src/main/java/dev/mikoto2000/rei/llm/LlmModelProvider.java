package dev.mikoto2000.rei.llm;

import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventPublisher;
import io.micrometer.observation.ObservationRegistry;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** 言語モデルモデルを用途や設定に応じて提供する窓口。 */
@Component
public class LlmModelProvider implements dev.mikoto2000.rei.secretary.SecretaryPrivateModel {
  /** 時間計測。 */
  private dev.mikoto2000.rei.timing.TimingExecution timing;

  /** 用途に応じたモデル利用で使用する時間計測を設定する。 */
  @Autowired(required = false)
  void setTiming(dev.mikoto2000.rei.timing.TimingExecution timing) {
    this.timing = timing;
  }

  /** 取得記録保存先。 */
  private dev.mikoto2000.rei.llm.capture.CaptureStore captureStore;

  /** 取得記録Customizers。 */
  private java.util.List<
          org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer>
      captureCustomizers = java.util.List.of();

  /** 取得記録観測内容一覧。 */
  private io.micrometer.observation.ObservationRegistry captureObservations =
      ObservationRegistry.NOOP;

  /** 取得記録Meters。 */
  private io.micrometer.core.instrument.MeterRegistry captureMeters;

  /** 用途に応じたモデル利用に関する設定取得記録を処理する。 */
  @Autowired(required = false)
  void configureCapture(
      dev.mikoto2000.rei.llm.capture.CaptureStore store,
      org.springframework.beans.factory.ObjectProvider<
              org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer>
          customizers,
      org.springframework.beans.factory.ObjectProvider<
              io.micrometer.observation.ObservationRegistry>
          observations,
      org.springframework.beans.factory.ObjectProvider<io.micrometer.core.instrument.MeterRegistry>
          meters) {
    captureStore = store;
    captureCustomizers = customizers.orderedStream().toList();
    captureObservations = observations.getIfAvailable(() -> ObservationRegistry.NOOP);
    captureMeters = meters.getIfAvailable();
  }

  /** 後から状況を確認できるよう、モデルを記録する。 */
  private ChatModel captureModel(ChatModel model, boolean custom) {
    if (captureStore == null || !(model instanceof OpenAiChatModel openAi)) return model;
    var configured =
        custom
            ? java.util.List
                .<org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer>of(
                    builder ->
                        builder
                            .interceptor(new ChatStreamTimeoutInterceptor())
                            .interceptor(new ShowUiSdkRequestInterceptor()))
            : captureCustomizers;
    return new CapturingChatModel(
        openAi,
        captureStore,
        configured,
        custom ? ObservationRegistry.NOOP : captureObservations,
        custom ? null : captureMeters);
  }

  /** 既定応答生成に使うモデル。 */
  private final ChatModel defaultChatModel;

  /** この機能の動作設定。 */
  private final LlmProperties properties;

  /** 実行状況の通知を生成する窓口。 */
  private final AgentEventFactory eventFactory;

  /** 実行状況の通知先。 */
  private final AgentEventPublisher eventPublisher;

  /** キャッシュ。 */
  private final Map<String, ChatModel> cache = new ConcurrentHashMap<>();

  /** 用途に応じたモデル利用を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public LlmModelProvider(ChatModel defaultChatModel, LlmProperties properties) {
    this(defaultChatModel, properties, null, null);
  }

  /** 指定された値や利用先を使って、LlmModelProviderを初期化する。 */
  @Autowired
  public LlmModelProvider(
      ChatModel defaultChatModel,
      LlmProperties properties,
      AgentEventFactory eventFactory,
      AgentEventPublisher eventPublisher) {
    this.defaultChatModel = defaultChatModel;
    this.properties = properties;
    this.eventFactory = eventFactory;
    this.eventPublisher = eventPublisher;
  }

  /** 用途に応じたモデル利用で用いる応答生成に使うモデルを返す。 */
  public ChatModel chatModel(String feature) {
    return cache.computeIfAbsent(feature, this::createFeatureModel);
  }

  /**
   * 用途に応じたモデル利用で用いる補助エージェント応答生成に使うモデルを返す。
   *
   * <p>Same server/model infrastructure; both primary and fallback must have no ambient tool
   * defaults.
   */
  public ChatModel subAgentChatModel() {
    dev.mikoto2000.rei.core.chat.ToolLoopSupport.requireNoDefaultTools(defaultChatModel);
    var model = chatModel(LlmFeature.CHAT);
    dev.mikoto2000.rei.core.chat.ToolLoopSupport.requireNoDefaultTools(model);
    return model;
  }

  /**
   * 用途に応じたモデル利用で用いる記憶応答生成に使うモデルを返す。
   *
   * <p>Memory extraction has no tools, including fallback model defaults.
   */
  public ChatModel memoryChatModel() {
    dev.mikoto2000.rei.core.chat.ToolLoopSupport.requireNoDefaultTools(defaultChatModel);
    var model = chatModel(LlmFeature.MEMORY);
    dev.mikoto2000.rei.core.chat.ToolLoopSupport.requireNoDefaultTools(model);
    return model;
  }

  /** 新たな対象を利用できるよう、機能モデルを作成する。 */
  private ChatModel createFeatureModel(String feature) {
    if (LlmFeature.VOICE_CORRECTION.equals(feature)) {
      // An explicitly selected correction endpoint must never fall back to a different remote
      // server.
      // Reuse CHAT connection settings when no correction endpoint is configured, without its
      // capture wrapper.
      var server = properties.feature(feature);
      if (server == null || !server.hasCustomServer()) server = properties.feature(LlmFeature.CHAT);
      var model =
          server != null && server.hasCustomServer()
              ? createOpenAiCompatibleChatModel(server)
              : defaultChatModel;
      dev.mikoto2000.rei.core.chat.ToolLoopSupport.requireNoDefaultTools(model);
      return model; // No Agent lifecycle exception publishing (may contain request/response text).
    }
    if (LlmFeature.COMPUTER_USE.equals(feature)
        || LlmFeature.COMPUTER_USE_PLANNER.equals(feature)
        || LlmFeature.ACTIVITY.equals(feature)
        || LlmFeature.ACTIVITY_BEHAVIOR.equals(feature))
      dev.mikoto2000.rei.core.chat.ToolLoopSupport.requireNoDefaultTools(defaultChatModel);
    LlmProperties.Server server = properties.feature(feature);
    ChatModel model =
        LlmFeature.CHAT.equals(feature) ? captureModel(defaultChatModel, false) : defaultChatModel;
    if (server != null && server.hasCustomServer()) {
      ChatModel primary = createOpenAiCompatibleChatModel(server);
      if (LlmFeature.CHAT.equals(feature)) primary = captureModel(primary, true);
      model =
          (LlmFeature.COMPUTER_USE.equals(feature)
                  || LlmFeature.COMPUTER_USE_PLANNER.equals(feature)
                  || LlmFeature.ACTIVITY.equals(feature)
                  || LlmFeature.ACTIVITY_BEHAVIOR.equals(feature))
              ? primary
              : new FallbackChatModel(feature, primary, model, primary.getOptions().getModel());
    }
    return eventFactory == null || eventPublisher == null
        ? model
        : new AgentEventChatModel(feature, model, eventFactory, eventPublisher, timing);
  }

  /** 用途に応じたモデル利用で用いるモデルを返す。 */
  public String model(String feature, String defaultModel) {
    LlmProperties.Server server = properties.feature(feature);
    if (server == null || server.getModel() == null || server.getModel().isBlank()) {
      return defaultModel != null && !defaultModel.isBlank()
          ? defaultModel
          : configuredDefaultModel();
    }
    return server.getModel();
  }

  /** 用途に応じたモデル利用で用いる音声補正応答生成に使うモデルを返す。 */
  public ChatModel voiceCorrectionChatModel() {
    var model = chatModel(LlmFeature.VOICE_CORRECTION);
    dev.mikoto2000.rei.core.chat.ToolLoopSupport.requireNoDefaultTools(model);
    return model;
  }

  /** 用途に応じたモデル利用で用いる画面操作利用応答生成に使うモデルを返す。 */
  public ChatModel computerUseChatModel() {
    var model = chatModel(LlmFeature.COMPUTER_USE);
    dev.mikoto2000.rei.core.chat.ToolLoopSupport.requireNoDefaultTools(model);
    return model;
  }

  /** 用途に応じたモデル利用で用いるチャット選択可能な設定を返す。 */
  public OpenAiChatOptions chatOptions(String feature, String defaultModel) {
    return chatOptions(feature, defaultModel, false);
  }

  /** 用途に応じたモデル利用で用いるチャット選択可能な設定を返す。 */
  public OpenAiChatOptions chatOptions(String feature, String defaultModel, boolean streamUsage) {
    OpenAiChatOptions.Builder options = chatOptionsBuilder(feature, defaultModel);
    if (streamUsage) {
      options.streamUsage(true);
    }
    return options.build();
  }

  @Override
  public dev.mikoto2000.rei.secretary.SecretaryPrivateModel.Descriptor
      privateSecretaryDescriptor() {
    var server = properties.feature("secretary-reply");
    if (server == null
        || !server.hasCustomServer()
        || server.getModel() == null
        || server.getModel().isBlank()) return null;
    try {
      var uri = java.net.URI.create(server.getBaseUrl()).normalize();
      if (uri.getUserInfo() != null
          || uri.getQuery() != null
          || uri.getFragment() != null
          || uri.getHost() == null) return null;
      boolean local =
          java.util.Set.of("localhost", "127.0.0.1", "[::1]", "::1").contains(uri.getHost());
      if (!"https".equals(uri.getScheme()) && !(local && "http".equals(uri.getScheme())))
        return null;
      String endpoint = uri.toASCIIString();
      String identity =
          endpoint
              + "\n"
              + server.getModel()
              + "\n"
              + server.getApiKey()
              + "\n"
              + server.getTemperature()
              + "\n"
              + server.getMaxOutputTokens();
      String fingerprint =
          java.util.HexFormat.of()
              .formatHex(
                  java.security.MessageDigest.getInstance("SHA-256")
                      .digest(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
      return new dev.mikoto2000.rei.secretary.SecretaryPrivateModel.Descriptor(
          endpoint, fingerprint);
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    } catch (IllegalArgumentException invalid) {
      return null;
    }
  }

  private final Map<String, ChatModel> privateSecretaryModels = new ConcurrentHashMap<>();

  /** No capture, fallback, memory advisors, observations or tool callbacks for private evidence. */
  public synchronized ChatModel privateSecretaryModel(String expectedFingerprint) {
    var descriptor = privateSecretaryDescriptor();
    if (descriptor == null || !descriptor.fingerprint().equals(expectedFingerprint))
      throw new IllegalStateException("Private model configuration changed or unavailable");
    if (privateSecretaryModels.containsKey(expectedFingerprint))
      return privateSecretaryModels.get(expectedFingerprint);
    if (!privateSecretaryModels.isEmpty())
      throw new IllegalStateException("Private model changes require restarting the application");
    var server = properties.feature("secretary-reply");
    var options =
        chatOptionsBuilder(server, server.getModel())
            .maxRetries(0)
            .timeout(java.time.Duration.ofSeconds(45))
            .baseUrl(OpenAiCompatibleEndpoint.baseUrl(server.getBaseUrl()))
            .apiKey(
                server.getApiKey() == null || server.getApiKey().isBlank()
                    ? "dummy-key"
                    : server.getApiKey())
            .build();
    var model =
        OpenAiChatModel.builder()
            .options(options)
            .observationRegistry(ObservationRegistry.NOOP)
            .build();
    dev.mikoto2000.rei.core.chat.ToolLoopSupport.requireNoDefaultTools(model);
    privateSecretaryModels.put(expectedFingerprint, model);
    return model;
  }

  /** 新たな対象を利用できるよう、開始AI互換応答生成に使うモデルを作成する。 */
  private ChatModel createOpenAiCompatibleChatModel(LlmProperties.Server server) {
    OpenAiChatOptions options =
        chatOptionsBuilder(server, modelForServer(server))
            .baseUrl(OpenAiCompatibleEndpoint.baseUrl(server.getBaseUrl()))
            .apiKey(
                server.getApiKey() == null || server.getApiKey().isBlank()
                    ? "dummy-key"
                    : server.getApiKey())
            .build();
    return OpenAiChatModel.builder()
        .options(options)
        .httpClientBuilderCustomizer(
            builder ->
                builder
                    .interceptor(new ChatStreamTimeoutInterceptor())
                    .interceptor(new ShowUiSdkRequestInterceptor()))
        .observationRegistry(ObservationRegistry.NOOP)
        .build();
  }

  /** 用途に応じたモデル利用で用いる設定済み既定モデルを返す。 */
  private String configuredDefaultModel() {
    return defaultChatModel == null || defaultChatModel.getOptions() == null
        ? null
        : defaultChatModel.getOptions().getModel();
  }

  /** 用途に応じたモデル利用で用いるモデル対象サーバーを返す。 */
  private String modelForServer(LlmProperties.Server server) {
    return server.getModel() == null || server.getModel().isBlank()
        ? configuredDefaultModel()
        : server.getModel();
  }

  /** 用途に応じたモデル利用で用いるチャット選択可能な設定組み立てを返す。 */
  private OpenAiChatOptions.Builder chatOptionsBuilder(String feature, String defaultModel) {
    if (LlmFeature.VOICE_CORRECTION.equals(feature)) {
      var correction = properties.feature(feature);
      var connection =
          correction != null && correction.hasCustomServer()
              ? correction
              : properties.feature(LlmFeature.CHAT);
      var options =
          connection != null && connection.hasCustomServer()
              ? chatOptionsBuilder(connection, modelForServer(connection))
                  .baseUrl(OpenAiCompatibleEndpoint.baseUrl(connection.getBaseUrl()))
                  .apiKey(
                      connection.getApiKey() == null || connection.getApiKey().isBlank()
                          ? "dummy-key"
                          : connection.getApiKey())
              : OpenAiChatOptions.builder().combineWith(defaultChatModel.getOptions().mutate());
      if (correction != null && correction.getModel() != null && !correction.getModel().isBlank())
        options.model(correction.getModel());
      if (correction != null && correction.getTemperature() != null)
        options.temperature(correction.getTemperature());
      return options;
    }
    LlmProperties.Server server = properties.feature(feature);
    OpenAiChatOptions.Builder options = chatOptionsBuilder(server, model(feature, defaultModel));
    // AI 2 uses a request's options as-is, rather than merging them with model defaults.
    if ((server == null || !server.hasCustomServer())
        && defaultChatModel != null
        && defaultChatModel.getOptions() != null) {
      var defaults = defaultChatModel.getOptions();
      options = OpenAiChatOptions.builder().combineWith(defaults.mutate()).combineWith(options);
      if (defaults instanceof OpenAiChatOptions openAi && openAi.getMaxCompletionTokens() != null) {
        // combineWith can retain both token fields, and maxTokens cannot clear itself
        // while maxCompletionTokens is present. Keep the configured parameter family.
        options
            .maxCompletionTokens(null)
            .maxTokens(null)
            .maxCompletionTokens(maxOutputTokens(server));
      }
    }
    return options;
  }

  /** 用途に応じたモデル利用で用いる最大出力トークン数を返す。 */
  private Integer maxOutputTokens(LlmProperties.Server server) {
    return server != null && server.getMaxOutputTokens() != null
        ? server.getMaxOutputTokens()
        : properties.getMaxOutputTokens();
  }

  /** 用途に応じたモデル利用で用いるチャット選択可能な設定組み立てを返す。 */
  private OpenAiChatOptions.Builder chatOptionsBuilder(LlmProperties.Server server, String model) {
    OpenAiChatOptions.Builder options =
        OpenAiChatOptions.builder().maxTokens(maxOutputTokens(server));
    if (model != null && !model.isBlank()) {
      options.model(model);
    }
    if (server != null && server.getTemperature() != null) {
      options.temperature(server.getTemperature());
    }
    return options;
  }
}
