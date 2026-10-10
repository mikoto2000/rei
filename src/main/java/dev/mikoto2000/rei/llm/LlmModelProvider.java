package dev.mikoto2000.rei.llm;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventPublisher;

import io.micrometer.observation.ObservationRegistry;

@Component
public class LlmModelProvider {
  private dev.mikoto2000.rei.timing.TimingExecution timing;
  @Autowired(required=false) void setTiming(dev.mikoto2000.rei.timing.TimingExecution timing) {this.timing=timing;}

  private dev.mikoto2000.rei.llm.capture.CaptureStore captureStore;
  private java.util.List<org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer> captureCustomizers=java.util.List.of();
  private io.micrometer.observation.ObservationRegistry captureObservations=ObservationRegistry.NOOP;
  private io.micrometer.core.instrument.MeterRegistry captureMeters;
  @Autowired(required=false)
  void configureCapture(dev.mikoto2000.rei.llm.capture.CaptureStore store,
      org.springframework.beans.factory.ObjectProvider<org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer> customizers,
      org.springframework.beans.factory.ObjectProvider<io.micrometer.observation.ObservationRegistry> observations,
      org.springframework.beans.factory.ObjectProvider<io.micrometer.core.instrument.MeterRegistry> meters){
    captureStore=store;captureCustomizers=customizers.orderedStream().toList();captureObservations=observations.getIfAvailable(()->ObservationRegistry.NOOP);
    captureMeters=meters.getIfAvailable();
  }
  private ChatModel captureModel(ChatModel model,boolean custom){
    if(captureStore==null||!(model instanceof OpenAiChatModel openAi))return model;
    var configured=custom?java.util.List.<org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer>of(
      builder->builder.interceptor(new ChatStreamTimeoutInterceptor()).interceptor(new ShowUiSdkRequestInterceptor())):captureCustomizers;
    return new CapturingChatModel(openAi,captureStore,configured,custom?ObservationRegistry.NOOP:captureObservations,custom?null:captureMeters);
  }
  private final ChatModel defaultChatModel;
  private final LlmProperties properties;
  private final AgentEventFactory eventFactory;
  private final AgentEventPublisher eventPublisher;
  private final Map<String, ChatModel> cache = new ConcurrentHashMap<>();

  public LlmModelProvider(ChatModel defaultChatModel, LlmProperties properties) {
    this(defaultChatModel, properties, null, null);
  }

  @Autowired
  public LlmModelProvider(ChatModel defaultChatModel, LlmProperties properties,
      AgentEventFactory eventFactory, AgentEventPublisher eventPublisher) {
    this.defaultChatModel = defaultChatModel;
    this.properties = properties;
    this.eventFactory = eventFactory;
    this.eventPublisher = eventPublisher;
  }

  public ChatModel chatModel(String feature) {
    return cache.computeIfAbsent(feature, this::createFeatureModel);
  }

  /** Same server/model infrastructure; both primary and fallback must have no ambient tool defaults. */
  public ChatModel subAgentChatModel() {
    dev.mikoto2000.rei.core.chat.ToolLoopSupport.requireNoDefaultTools(defaultChatModel);
    var model = chatModel(LlmFeature.CHAT);
    dev.mikoto2000.rei.core.chat.ToolLoopSupport.requireNoDefaultTools(model);
    return model;
  }

  /** Memory extraction has no tools, including fallback model defaults. */
  public ChatModel memoryChatModel() {
    dev.mikoto2000.rei.core.chat.ToolLoopSupport.requireNoDefaultTools(defaultChatModel);
    var model=chatModel(LlmFeature.MEMORY);
    dev.mikoto2000.rei.core.chat.ToolLoopSupport.requireNoDefaultTools(model);
    return model;
  }

  private ChatModel createFeatureModel(String feature) {
    if (LlmFeature.COMPUTER_USE.equals(feature) || LlmFeature.COMPUTER_USE_PLANNER.equals(feature) || LlmFeature.ACTIVITY.equals(feature) || LlmFeature.ACTIVITY_BEHAVIOR.equals(feature))
      dev.mikoto2000.rei.core.chat.ToolLoopSupport.requireNoDefaultTools(defaultChatModel);
    LlmProperties.Server server = properties.feature(feature);
    ChatModel model = LlmFeature.CHAT.equals(feature) ? captureModel(defaultChatModel,false) : defaultChatModel;
    if (server != null && server.hasCustomServer()) {
      ChatModel primary = createOpenAiCompatibleChatModel(server);
      if(LlmFeature.CHAT.equals(feature))primary=captureModel(primary,true);
      model = (LlmFeature.COMPUTER_USE.equals(feature) || LlmFeature.COMPUTER_USE_PLANNER.equals(feature) || LlmFeature.ACTIVITY.equals(feature) || LlmFeature.ACTIVITY_BEHAVIOR.equals(feature)) ? primary
          : new FallbackChatModel(feature, primary, model, primary.getOptions().getModel());
    }
    return eventFactory == null || eventPublisher == null
        ? model
        : new AgentEventChatModel(feature, model, eventFactory, eventPublisher,timing);
  }

  public String model(String feature, String defaultModel) {
    LlmProperties.Server server = properties.feature(feature);
    if (server == null || server.getModel() == null || server.getModel().isBlank()) {
      return defaultModel != null && !defaultModel.isBlank() ? defaultModel : configuredDefaultModel();
    }
    return server.getModel();
  }

  public ChatModel computerUseChatModel() {
    var model = chatModel(LlmFeature.COMPUTER_USE);
    dev.mikoto2000.rei.core.chat.ToolLoopSupport.requireNoDefaultTools(model);
    return model;
  }

  public OpenAiChatOptions chatOptions(String feature, String defaultModel) {
    return chatOptions(feature, defaultModel, false);
  }

  public OpenAiChatOptions chatOptions(String feature, String defaultModel, boolean streamUsage) {
    OpenAiChatOptions.Builder options = chatOptionsBuilder(feature, defaultModel);
    if (streamUsage) {
      options.streamUsage(true);
    }
    return options.build();
  }

  private ChatModel createOpenAiCompatibleChatModel(LlmProperties.Server server) {
    OpenAiChatOptions options = chatOptionsBuilder(server, modelForServer(server))
        .baseUrl(OpenAiCompatibleEndpoint.baseUrl(server.getBaseUrl()))
        .apiKey(server.getApiKey() == null || server.getApiKey().isBlank() ? "dummy-key" : server.getApiKey())
        .build();
    return OpenAiChatModel.builder()
        .options(options)
        .httpClientBuilderCustomizer(builder -> builder
            .interceptor(new ChatStreamTimeoutInterceptor())
            .interceptor(new ShowUiSdkRequestInterceptor()))
        .observationRegistry(ObservationRegistry.NOOP)
        .build();
  }

  private String configuredDefaultModel() {
    return defaultChatModel == null || defaultChatModel.getOptions() == null
        ? null : defaultChatModel.getOptions().getModel();
  }

  private String modelForServer(LlmProperties.Server server) {
    return server.getModel() == null || server.getModel().isBlank()
        ? configuredDefaultModel() : server.getModel();
  }

  private OpenAiChatOptions.Builder chatOptionsBuilder(String feature, String defaultModel) {
    LlmProperties.Server server = properties.feature(feature);
    OpenAiChatOptions.Builder options = chatOptionsBuilder(server, model(feature, defaultModel));
    // AI 2 uses a request's options as-is, rather than merging them with model defaults.
    if ((server == null || !server.hasCustomServer()) && defaultChatModel != null
        && defaultChatModel.getOptions() != null) {
      var defaults = defaultChatModel.getOptions();
      options = OpenAiChatOptions.builder().combineWith(defaults.mutate()).combineWith(options);
      if (defaults instanceof OpenAiChatOptions openAi && openAi.getMaxCompletionTokens() != null) {
        // combineWith can retain both token fields, and maxTokens cannot clear itself
        // while maxCompletionTokens is present. Keep the configured parameter family.
        options.maxCompletionTokens(null).maxTokens(null).maxCompletionTokens(maxOutputTokens(server));
      }
    }
    return options;
  }

  private Integer maxOutputTokens(LlmProperties.Server server) {
    return server != null && server.getMaxOutputTokens() != null
        ? server.getMaxOutputTokens() : properties.getMaxOutputTokens();
  }

  private OpenAiChatOptions.Builder chatOptionsBuilder(LlmProperties.Server server, String model) {
    OpenAiChatOptions.Builder options = OpenAiChatOptions.builder()
        .maxTokens(maxOutputTokens(server));
    if (model != null && !model.isBlank()) {
      options.model(model);
    }
    if (server != null && server.getTemperature() != null) {
      options.temperature(server.getTemperature());
    }
    return options;
  }
}
