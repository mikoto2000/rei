package dev.mikoto2000.rei.llm;

import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;

/** Native Chat Completions options and the pinned Spring AI merge contract. */
final class ChatCompletionsAdapter implements LlmProtocolAdapter {
  static OpenAiChatOptions.Builder defaultOutputOptions(
      ChatOptions defaults, int outputTokens, Integer maxTools) {
    if (defaults instanceof org.springframework.ai.openai.responses.OpenAiResponsesChatOptions)
      throw new IllegalStateException(
          "Responses is unavailable until durable agent integration is verified");
    var options = OpenAiChatOptions.builder();
    if (maxTools != null)
      options.toolContext(java.util.Map.of(LlmCapabilities.MAX_TOOLS_CONTEXT_KEY, maxTools));
    if (defaults instanceof OpenAiChatOptions openAi && openAi.getMaxCompletionTokens() != null)
      return options.maxCompletionTokens(outputTokens);
    return options.maxTokens(outputTokens);
  }

  @Override
  public OpenAiChatOptions options(ResolvedLlmEndpoint endpoint) {
    if (endpoint.apiType() != LlmApiType.CHAT_COMPLETIONS)
      throw new IllegalArgumentException("Chat Completions endpoint required");
    var options = OpenAiChatOptions.builder().maxTokens(endpoint.maxOutputTokens());
    if (endpoint.capabilities().maxTools() != null)
      options.toolContext(
          java.util.Map.of(
              LlmCapabilities.MAX_TOOLS_CONTEXT_KEY, endpoint.capabilities().maxTools()));
    if (endpoint.model() != null && !endpoint.model().isBlank()) options.model(endpoint.model());
    if (endpoint.temperature() != null) options.temperature(endpoint.temperature());
    return options.maxRetries(0).build();
  }

  OpenAiChatOptions.Builder withDefaults(ResolvedLlmEndpoint endpoint, ChatOptions defaults) {
    var options = options(endpoint).mutate();
    if (defaults == null) return options;
    options = OpenAiChatOptions.builder().combineWith(defaults.mutate()).combineWith(options);
    if (defaults instanceof OpenAiChatOptions openAi && openAi.getMaxCompletionTokens() != null)
      options
          .maxCompletionTokens(null)
          .maxTokens(null)
          .maxCompletionTokens(endpoint.maxOutputTokens());
    return options;
  }
}
