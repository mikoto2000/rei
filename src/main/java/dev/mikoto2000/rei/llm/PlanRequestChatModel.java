package dev.mikoto2000.rei.llm;

import dev.mikoto2000.rei.modeloptions.ChatPlanAccounts;
import java.util.function.Supplier;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/** One account admission per model request, inside the existing event and outage decoration. */
final class PlanRequestChatModel implements ChatModel {
  private final ChatModel delegate;
  private final Supplier<ChatPlanAccounts.Request> admission;

  PlanRequestChatModel(ChatModel delegate, Supplier<ChatPlanAccounts.Request> admission) {
    this.delegate = java.util.Objects.requireNonNull(delegate);
    this.admission = java.util.Objects.requireNonNull(admission);
  }

  @Override
  public ChatResponse call(Prompt prompt) {
    return delegate.call(prompt);
  }

  @Override
  public Flux<ChatResponse> stream(Prompt prompt) {
    return delegate.stream(prompt);
  }
}
