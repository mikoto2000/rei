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

import io.micrometer.observation.ObservationRegistry;

@Component
public class LlmModelProvider {

  private final ChatModel defaultChatModel;
  private final LlmProperties properties;
  private final Map<String, ChatModel> cache = new ConcurrentHashMap<>();

  public LlmModelProvider(ChatModel defaultChatModel, LlmProperties properties) {
    this.defaultChatModel = defaultChatModel;
    this.properties = properties;
  }

  public ChatModel chatModel(String feature) {
    LlmProperties.Server server = properties.feature(feature);
    if (server == null || !server.hasCustomServer()) {
      return defaultChatModel;
    }
    return cache.computeIfAbsent(feature, ignored -> new FallbackChatModel(feature,
        createOpenAiCompatibleChatModel(server), defaultChatModel, server.getModel()));
  }

  public String model(String feature, String defaultModel) {
    LlmProperties.Server server = properties.feature(feature);
    if (server == null || server.getModel() == null || server.getModel().isBlank()) {
      return defaultModel;
    }
    return server.getModel();
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

  private OpenAiChatOptions.Builder chatOptionsBuilder(String feature, String defaultModel) {
    LlmProperties.Server server = properties.feature(feature);
    return chatOptionsBuilder(server, model(feature, defaultModel));
  }

  private OpenAiChatOptions.Builder chatOptionsBuilder(LlmProperties.Server server, String model) {
    OpenAiChatOptions.Builder options = OpenAiChatOptions.builder()
        .maxTokens(properties.getMaxOutputTokens());
    if (model != null && !model.isBlank()) {
      options.model(model);
    }
    if (server != null && server.getTemperature() != null) {
      options.temperature(server.getTemperature());
    }
    return options;
  }
}
