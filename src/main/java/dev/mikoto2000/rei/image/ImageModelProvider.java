package dev.mikoto2000.rei.image;

import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventPublisher;
import dev.mikoto2000.rei.llm.LlmFeature;
import dev.mikoto2000.rei.llm.LlmProperties;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.openai.OpenAiImageModel;
import org.springframework.ai.openai.OpenAiImageOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** 画像モデルを用途や設定に応じて提供する窓口。 */
@Component
public class ImageModelProvider {

  /** 既定画像モデル提供元。 */
  private final ObjectProvider<ImageModel> defaultImageModelProvider;

  /** この機能の動作設定。 */
  private final LlmProperties properties;

  /** 画像設定。 */
  private final ImageProperties imageProperties;

  /** 実行状況の通知を生成する窓口。 */
  private final AgentEventFactory eventFactory;

  /** 実行状況の通知先。 */
  private final AgentEventPublisher eventPublisher;

  /** キャッシュ。 */
  private final Map<String, ImageModel> cache = new ConcurrentHashMap<>();

  /** 指定された値や利用先を使って、ImageModelProviderを初期化する。 */
  @Autowired
  public ImageModelProvider(
      ObjectProvider<ImageModel> defaultImageModelProvider,
      LlmProperties properties,
      ImageProperties imageProperties,
      AgentEventFactory eventFactory,
      AgentEventPublisher eventPublisher) {
    this.defaultImageModelProvider = defaultImageModelProvider;
    this.properties = properties;
    this.imageProperties = imageProperties;
    this.eventFactory = eventFactory;
    this.eventPublisher = eventPublisher;
  }

  /** 画像の生成を利用するため、指定を補って共通の初期化へ引き渡す。 */
  ImageModelProvider(ImageModel defaultImageModel, LlmProperties properties) {
    this(
        new FixedObjectProvider<>(defaultImageModel),
        properties,
        new ImageProperties(),
        null,
        null);
  }

  /** 画像の生成で用いる画像モデルを返す。 */
  public ImageModel imageModel() {
    return cache.computeIfAbsent(LlmFeature.IMAGE_GENERATION, ignored -> createImageModel());
  }

  /** 新たな対象を利用できるよう、画像モデルを作成する。 */
  private ImageModel createImageModel() {
    ImageModel defaultImageModel = defaultImageModel();
    LlmProperties.Server server = properties.feature(LlmFeature.IMAGE_GENERATION);
    ImageModel model =
        server == null || !server.hasCustomServer()
            ? defaultImageModel
            : new FallbackImageModel(
                LlmFeature.IMAGE_GENERATION,
                createOpenAiCompatibleImageModel(server),
                defaultImageModel,
                server.getModel());
    return eventFactory == null || eventPublisher == null
        ? model
        : new AgentEventImageModel(
            LlmFeature.IMAGE_GENERATION, model, eventFactory, eventPublisher);
  }

  /** 画像の生成で用いるモデルを返す。 */
  public String model(String overrideModel) {
    if (overrideModel != null && !overrideModel.isBlank()) {
      return overrideModel;
    }
    LlmProperties.Server server = properties.feature(LlmFeature.IMAGE_GENERATION);
    if (server != null && server.getModel() != null && !server.getModel().isBlank()) {
      return server.getModel();
    }
    return configuredModel(defaultImageModel());
  }

  /** 画像の生成で用いる設定済みモデルを返す。 */
  static String configuredModel(ImageModel model) {
    return model instanceof OpenAiImageModel openAi && openAi.getOptions() != null
        ? openAi.getOptions().getModel()
        : null;
  }

  /** 画像の生成で用いる既定画像モデルを返す。 */
  private ImageModel defaultImageModel() {
    ImageModel imageModel = defaultImageModelProvider.getIfAvailable();
    if (imageModel == null) {
      throw new IllegalStateException("画像生成用 ImageModel が構成されていません");
    }
    return imageModel;
  }

  /** 新たな対象を利用できるよう、開始AI互換画像モデルを作成する。 */
  private ImageModel createOpenAiCompatibleImageModel(LlmProperties.Server server) {
    OpenAiImageOptions.Builder options =
        OpenAiImageOptions.builder()
            .baseUrl(dev.mikoto2000.rei.llm.OpenAiCompatibleEndpoint.baseUrl(server.getBaseUrl()))
            .apiKey(
                server.getApiKey() == null || server.getApiKey().isBlank()
                    ? "dummy-key"
                    : server.getApiKey())
            .timeout(Duration.ofSeconds(Math.max(1, imageProperties.getTimeoutSeconds())));
    String model = model(null);
    if (model != null && !model.isBlank()) {
      options.model(model);
    }
    return OpenAiImageModel.builder()
        .options(options.build())
        .observationRegistry(ObservationRegistry.NOOP)
        .build();
  }

  /**
   * 画像の生成で受け渡す固定対象データ提供元を表す。
   *
   * @param value 値
   */
  private record FixedObjectProvider<T>(T value) implements ObjectProvider<T> {
    /** 画像の生成で参照する対象データを返す。 */
    @Override
    public T getObject(Object... args) {
      return value;
    }

    /** 画像の生成で参照する条件付き利用可能を返す。 */
    @Override
    public T getIfAvailable() {
      return value;
    }

    /** 画像の生成で参照する条件付き一意なを返す。 */
    @Override
    public T getIfUnique() {
      return value;
    }

    /** 画像の生成で参照する対象データを返す。 */
    @Override
    public T getObject() {
      return value;
    }
  }
}
