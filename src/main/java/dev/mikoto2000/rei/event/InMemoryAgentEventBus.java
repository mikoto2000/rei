package dev.mikoto2000.rei.event;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * プロセス内 In-memory Event Bus。
 *
 * <p>publish されたイベントに単調増加の sequence を付与し、各 Listener に通知する。
 * Listener の例外は Agent Core 全体の実行を止めないよう捕捉し、logging で観測可能にする。</p>
 */
@Component
public class InMemoryAgentEventBus implements AgentEventBus, AgentEventPublisher {

  private static final Logger log = LoggerFactory.getLogger(InMemoryAgentEventBus.class);

  private final List<AgentEventListener> listeners = new CopyOnWriteArrayList<>();
  private final AtomicLong sequence = new AtomicLong(0L);

  @Override
  public Subscription subscribe(AgentEventListener listener) {
    if (listener == null) {
      throw new IllegalArgumentException("listener must not be null");
    }
    listeners.add(listener);
    return new Subscription() {
      private volatile boolean unsubscribed = false;

      @Override
      public void unsubscribe() {
        if (unsubscribed) {
          return;
        }
        unsubscribed = true;
        listeners.remove(listener);
      }
    };
  }

  @Override
  public void publish(AgentEvent event) {
    if (event == null) {
      throw new IllegalArgumentException("event must not be null");
    }
    AgentEvent sequenced = withSequence(event);
    for (AgentEventListener listener : listeners) {
      try {
        listener.onEvent(sequenced);
      } catch (RuntimeException e) {
        log.warn("Agent event listener failed: type={}, listener={}", sequenced.type(), listener.getClass().getName(), e);
      }
    }
  }

  private AgentEvent withSequence(AgentEvent event) {
    long next = sequence.incrementAndGet();
    return new AgentEvent(
        event.id(),
        next,
        event.timestamp(),
        event.type(),
        event.version(),
        event.sessionId(),
        event.turnId(),
        event.runId(),
        event.correlationId(),
        event.parentEventId(),
        event.payload());
  }
}
