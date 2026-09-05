package dev.mikoto2000.rei.llm;

import java.util.UUID;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventPublisher;
import reactor.core.publisher.Flux;

/** Publishes lifecycle events around every text LLM invocation. */
final class AgentEventChatModel implements ChatModel {

  private final String feature;
  private final ChatModel delegate;
  private final AgentEventFactory eventFactory;
  private final AgentEventPublisher eventPublisher;

  AgentEventChatModel(String feature, ChatModel delegate, AgentEventFactory eventFactory,
      AgentEventPublisher eventPublisher) {
    this.feature = feature;
    this.delegate = delegate;
    this.eventFactory = eventFactory;
    this.eventPublisher = eventPublisher;
  }

  @Override
  public ChatResponse call(Prompt prompt) {
    String requestId = UUID.randomUUID().toString();
    long startedAtNanos = System.nanoTime();
    eventPublisher.publish(eventFactory.llmRequestStarted(null, requestId, feature));
    try {
      ChatResponse response = delegate.call(prompt);
      eventPublisher.publish(eventFactory.llmResponseCompleted(null, requestId, elapsedMillis(startedAtNanos)));
      return response;
    } catch (RuntimeException e) {
      eventPublisher.publish(eventFactory.llmRequestFailed(null, requestId, elapsedMillis(startedAtNanos), e));
      throw e;
    }
  }

  @Override
  public Flux<ChatResponse> stream(Prompt prompt) {
    return Flux.defer(() -> {
      String requestId = UUID.randomUUID().toString();
      long startedAtNanos = System.nanoTime();
      java.util.concurrent.atomic.AtomicBoolean firstTokenPublished = new java.util.concurrent.atomic.AtomicBoolean();
      eventPublisher.publish(eventFactory.llmRequestStarted(null, requestId, feature));
      return delegate.stream(prompt)
          .doOnNext(response -> {
            if (firstTokenPublished.compareAndSet(false, true)) {
              eventPublisher.publish(eventFactory.llmResponseFirstToken(null, requestId, elapsedMillis(startedAtNanos)));
            }
          })
          .doOnComplete(() -> eventPublisher.publish(
              eventFactory.llmResponseCompleted(null, requestId, elapsedMillis(startedAtNanos))))
          .doOnError(error -> eventPublisher.publish(
              eventFactory.llmRequestFailed(null, requestId, elapsedMillis(startedAtNanos), error)));
    });
  }

  @Override
  public ChatOptions getDefaultOptions() {
    return delegate.getDefaultOptions();
  }

  private long elapsedMillis(long startedAtNanos) {
    return (System.nanoTime() - startedAtNanos) / 1_000_000L;
  }
}
