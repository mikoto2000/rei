package dev.mikoto2000.rei.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import reactor.core.publisher.Flux;

class FallbackChatModel implements ChatModel {

  private static final Logger log = LoggerFactory.getLogger(FallbackChatModel.class);

  private final String feature;
  private final ChatModel primary;
  private final ChatModel fallback;
  private final String primaryModel;

  FallbackChatModel(String feature, ChatModel primary, ChatModel fallback, String primaryModel) {
    this.feature = feature;
    this.primary = primary;
    this.fallback = fallback;
    this.primaryModel = primaryModel;
  }

  @Override
  public ChatResponse call(Prompt prompt) {
    try {
      return primary.call(prompt);
    } catch (RuntimeException e) {
      logFeatureFailure("call", e);
      return fallback.call(fallbackPrompt(prompt));
    }
  }

  @Override
  public Flux<ChatResponse> stream(Prompt prompt) {
    return Flux.defer(() -> primary.stream(prompt))
        .onErrorResume(error -> {
          logFeatureFailure("stream", error);
          return fallback.stream(fallbackPrompt(prompt));
        });
  }

  @Override
  public ChatOptions getDefaultOptions() {
    return primary.getDefaultOptions();
  }

  private Prompt fallbackPrompt(Prompt prompt) {
    ChatOptions options = prompt.getOptions();
    if (options == null || primaryModel == null || primaryModel.isBlank()) {
      return prompt;
    }
    if (!primaryModel.equals(options.getModel())) {
      return prompt;
    }
    ChatOptions fallbackOptions = fallbackOptionsWithoutPrimaryModel(options);
    return Prompt.builder()
        .messages(prompt.getInstructions())
        .chatOptions(fallbackOptions)
        .build();
  }

  private ChatOptions fallbackOptionsWithoutPrimaryModel(ChatOptions options) {
    if (options instanceof OpenAiChatOptions openAiOptions) {
      OpenAiChatOptions fallbackOptions = openAiOptions.copy();
      fallbackOptions.setModel(null);
      return fallbackOptions;
    }
    return ChatOptions.builder()
        .model(null)
        .frequencyPenalty(options.getFrequencyPenalty())
        .maxTokens(options.getMaxTokens())
        .presencePenalty(options.getPresencePenalty())
        .stopSequences(options.getStopSequences())
        .temperature(options.getTemperature())
        .topK(options.getTopK())
        .topP(options.getTopP())
        .build();
  }

  private void logFeatureFailure(String operation, Throwable error) {
    WebClientResponseException responseException = findResponseException(error);
    if (responseException == null) {
      log.warn("Feature LLM {} failed. Falling back to default LLM: feature={}", operation, feature, error);
      return;
    }
    log.warn(
        "Feature LLM {} failed. Falling back to default LLM: feature={}, status={}, responseBody={}",
        operation,
        feature,
        responseException.getStatusCode(),
        responseException.getResponseBodyAsString(),
        error);
  }

  private WebClientResponseException findResponseException(Throwable error) {
    if (error == null) {
      return null;
    }
    if (error instanceof WebClientResponseException responseException) {
      return responseException;
    }
    WebClientResponseException cause = findResponseException(error.getCause());
    if (cause != null) {
      return cause;
    }
    for (Throwable suppressed : error.getSuppressed()) {
      WebClientResponseException suppressedResponse = findResponseException(suppressed);
      if (suppressedResponse != null) {
        return suppressedResponse;
      }
    }
    return null;
  }
}
