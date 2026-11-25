package dev.mikoto2000.rei.llm;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/** Test-only accounting wrapper; execution remains with the existing model and ToolLoop. */
final class ResponsesAcceptanceMeter implements ChatModel {
  private final ChatModel delegate;
  ResponsesAcceptanceMeter(ChatModel delegate, ResponsesAcceptanceBudget budget) { this.delegate = delegate; }
  public ChatResponse call(Prompt prompt) { return delegate.call(prompt); }
  public Flux<ChatResponse> stream(Prompt prompt) { return delegate.stream(prompt); }
  public ChatOptions getOptions() { return delegate.getOptions(); }
}
