package dev.mikoto2000.rei.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

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
      log.warn("Feature LLM call failed. Falling back to default LLM: feature={}", feature, e);
      return fallback.call(fallbackPrompt(prompt));
    }
  }

  @Override
  public Flux<ChatResponse> stream(Prompt prompt) {
    return Flux.defer(() -> primary.stream(prompt))
        .onErrorResume(error -> {
          log.warn("Feature LLM stream failed. Falling back to default LLM: feature={}", feature, error);
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
    ChatOptions fallbackOptions = options.copy().mutate().model(null).build();
    return Prompt.builder()
        .messages(prompt.getInstructions())
        .chatOptions(fallbackOptions)
        .build();
  }
}
