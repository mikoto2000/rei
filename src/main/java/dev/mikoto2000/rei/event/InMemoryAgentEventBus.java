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
  private final java.util.ArrayDeque<AgentEvent> pending = new java.util.ArrayDeque<>();
  private boolean dispatching;
  private final ReplayBuffer replay;

  public InMemoryAgentEventBus() { this(new ReplayBuffer(10_000, java.time.Clock.systemUTC())); }
  private InMemoryAgentEventBus(ReplayBuffer replay) { this.replay = replay; }
  public static InMemoryAgentEventBus withReplayBuffer(ReplayBuffer replay) { return new InMemoryAgentEventBus(replay); }

  @Override public synchronized ReplaySubscription subscribe(String runId, long fromSequence, AgentEventListener listener) {
    var snapshot = replay.snapshot(runId, fromSequence);
    var subscription = subscribe(event -> {
      if (runId.equals(event.runId()) && event.sequence() > snapshot.latestSequence()) listener.onEvent(event);
    });
    return new ReplaySubscription(snapshot.events(), subscription, snapshot.latestSequence(), snapshot.terminalSequence());
  }
  @Override public synchronized void purgeRun(String runId) { replay.purgeRun(runId); }
  @Override public synchronized void purgeExpired() { replay.purgeExpired(); }
  @Override public synchronized void purgeExpired(java.util.Set<String> protectedRuns) { replay.purgeExpired(protectedRuns); }

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
  public synchronized void publish(AgentEvent event) {
    if (event == null) {
      throw new IllegalArgumentException("event must not be null");
    }
    pending.addLast(withSequence(event));
    if (dispatching) return;
    dispatching = true;
    try {
      while (!pending.isEmpty()) {
        AgentEvent sequenced = pending.removeFirst();
        replay.append(sequenced);
        for (AgentEventListener listener : listeners) {
          try {
            listener.onEvent(sequenced);
          } catch (RuntimeException e) {
            log.warn("Agent event listener failed: type={}, listener={}", sequenced.type(), listener.getClass().getName(), e);
          }
        }
      }
    } finally {
      dispatching = false;
    }
  }

  @Override
  public long lastSequence() {
    return sequence.get();
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
        event.payload(), event.projectId());
  }
}
