package dev.mikoto2000.rei.core.service;

import java.util.List;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

import reactor.core.publisher.Flux;

public class RoundRobinChatModel implements ChatModel {

  private final RoundRobinSelector<Delegate> selector;

  public RoundRobinChatModel(List<ChatModel> delegates) {
    this(delegates.stream().map(delegate -> new Delegate(delegate, null)).toList(), true);
  }

  public RoundRobinChatModel(List<Delegate> delegates, boolean ignored) {
    this.selector = new RoundRobinSelector<>(delegates);
  }

  @Override
  public ChatResponse call(Prompt prompt) {
    Delegate delegate = selector.next();
    return delegate.model().call(withModel(prompt, delegate.modelName()));
  }

  @Override
  public Flux<ChatResponse> stream(Prompt prompt) {
    Delegate delegate = selector.next();
    return delegate.model().stream(withModel(prompt, delegate.modelName()));
  }

  @Override
  public ChatOptions getDefaultOptions() {
    return selector.first().model().getDefaultOptions();
  }

  public int delegateCount() {
    return selector.size();
  }

  private Prompt withModel(Prompt prompt, String modelName) {
    if (modelName == null || modelName.isBlank()) {
      return prompt;
    }
    ChatOptions options = prompt.getOptions();
    OpenAiChatOptions openAiOptions;
    if (options instanceof OpenAiChatOptions existing) {
      openAiOptions = existing.copy();
    } else {
      openAiOptions = OpenAiChatOptions.builder().build();
    }
    openAiOptions.setModel(modelName);
    return new Prompt(prompt.getInstructions(), openAiOptions);
  }

  public record Delegate(ChatModel model, String modelName) {
  }
}
