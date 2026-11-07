package dev.mikoto2000.rei.event;

import java.util.function.Consumer;
import java.util.function.Predicate;
import org.springframework.stereotype.Component;

/** イベントの履歴と診断情報を同じCOMMITで確定し、通常配送より先に完了させる。 */
@Component
public final class StreamingEventPersistence implements AutoCloseable {
  private final Predicate<AgentEvent> canPersist;
  private final Consumer<AgentEvent> persistEvent;
  private final AgentEventBus.Subscription subscription;
  private volatile boolean closed;

  public StreamingEventPersistence(
      AgentEventBus bus, ProjectAgentEventStore events, ProfileEventLogStore profile) {
    if (events instanceof SqliteProjectAgentEventStore sqlite) {
      this.canPersist = event -> profile.hasManagedLog() && event.projectId() != null;
      this.persistEvent = event -> profile.persistStreamingEvent(sqlite, event);
    } else {
      this.canPersist = event -> false;
      this.persistEvent = event -> {};
    }
    AgentEventBus.Subscription registered;
    try {
      registered = bus.subscribePersistence(this::persist);
    } catch (UnsupportedOperationException legacyBus) {
      registered = null;
    }
    subscription = registered;
  }

  /** ファイル出力や旧busでは既存の保存経路を維持する。 */
  boolean handles(AgentEvent event) {
    return !closed && subscription != null && canPersist.test(event);
  }

  private void persist(AgentEvent event) {
    if (handles(event)) persistEvent.accept(event);
  }

  @Override
  @jakarta.annotation.PreDestroy
  public void close() {
    closed = true;
    if (subscription != null) subscription.unsubscribe();
  }
}
