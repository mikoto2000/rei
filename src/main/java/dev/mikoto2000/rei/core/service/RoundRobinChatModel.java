package dev.mikoto2000.rei.core.service;

import java.util.List;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import reactor.core.publisher.Flux;

public class RoundRobinChatModel implements ChatModel {

  private final RoundRobinSelector<ChatModel> selector;

  public RoundRobinChatModel(List<ChatModel> delegates) {
    this.selector = new RoundRobinSelector<>(delegates);
  }

  @Override
  public ChatResponse call(Prompt prompt) {
    return selector.next().call(prompt);
  }

  @Override
  public Flux<ChatResponse> stream(Prompt prompt) {
    return selector.next().stream(prompt);
  }

  @Override
  public ChatOptions getDefaultOptions() {
    return selector.first().getDefaultOptions();
  }

  public int delegateCount() {
    return selector.size();
  }
}
