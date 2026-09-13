package dev.mikoto2000.rei.llm;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventPublisher;

import io.micrometer.observation.ObservationRegistry;

@Component
public class LlmModelProvider {

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

  private ChatModel createFeatureModel(String feature) {
    if (LlmFeature.COMPUTER_USE.equals(feature) || LlmFeature.COMPUTER_USE_PLANNER.equals(feature))
      dev.mikoto2000.rei.core.chat.ToolLoopSupport.requireNoDefaultTools(defaultChatModel);
    LlmProperties.Server server = properties.feature(feature);
    ChatModel model = defaultChatModel;
    if (server != null && server.hasCustomServer()) {
      ChatModel primary = createOpenAiCompatibleChatModel(server);
      model = (LlmFeature.COMPUTER_USE.equals(feature) || LlmFeature.COMPUTER_USE_PLANNER.equals(feature)) ? primary
          : new FallbackChatModel(feature, primary, defaultChatModel, server.getModel());
    }
    return eventFactory == null || eventPublisher == null
        ? model
        : new AgentEventChatModel(feature, model, eventFactory, eventPublisher);
  }

  public String model(String feature, String defaultModel) {
    LlmProperties.Server server = properties.feature(feature);
    if (server == null || server.getModel() == null || server.getModel().isBlank()) {
      return defaultModel;
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
    OpenAiApi api = OpenAiApi.builder()
        .baseUrl(server.getBaseUrl())
        .apiKey(server.getApiKey() == null || server.getApiKey().isBlank() ? "dummy-key" : server.getApiKey())
        // These builders bypass Boot customizers, so use the OS resolver explicitly for .local hosts.
        .restClientBuilder(org.springframework.web.client.RestClient.builder()
            .requestInterceptor(new dev.mikoto2000.rei.computeruse.ShowUiRequestInterceptor())
            .requestFactory(new org.springframework.http.client.ReactorClientHttpRequestFactory(
                featureHttpClient())))
        .webClientBuilder(org.springframework.web.reactive.function.client.WebClient.builder()
            .clientConnector(new org.springframework.http.client.reactive.ReactorClientHttpConnector(
                featureHttpClient())))
        .build();
    OpenAiChatOptions.Builder options = chatOptionsBuilder(server, server.getModel());
    return OpenAiChatModel.builder()
        .openAiApi(api)
        .defaultOptions(options.build())
        .toolCallingManager(ToolCallingManager.builder()
            .observationRegistry(ObservationRegistry.NOOP)
            .build())
        .retryTemplate(RetryUtils.DEFAULT_RETRY_TEMPLATE)
        .observationRegistry(ObservationRegistry.NOOP)
        .build();
  }

  static reactor.netty.http.client.HttpClient featureHttpClient() {
    return reactor.netty.http.client.HttpClient.create()
        .resolver(io.netty.resolver.DefaultAddressResolverGroup.INSTANCE);
  }

  private OpenAiChatOptions.Builder chatOptionsBuilder(String feature, String defaultModel) {
    LlmProperties.Server server = properties.feature(feature);
    return chatOptionsBuilder(server, model(feature, defaultModel));
  }

  private OpenAiChatOptions.Builder chatOptionsBuilder(LlmProperties.Server server, String model) {
    OpenAiChatOptions.Builder options = OpenAiChatOptions.builder()
        .maxTokens(server != null && server.getMaxOutputTokens() != null
            ? server.getMaxOutputTokens() : properties.getMaxOutputTokens());
    if (model != null && !model.isBlank()) {
      options.model(model);
    }
    if (server != null && server.getTemperature() != null) {
      options.temperature(server.getTemperature());
    }
    return options;
  }
}
