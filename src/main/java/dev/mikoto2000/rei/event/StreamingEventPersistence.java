package dev.mikoto2000.rei.event;

import org.springframework.stereotype.Component;

/** ストリームの履歴と診断情報を同じCOMMITで確定し、通常配送より先に完了させる。 */
@Component
public final class StreamingEventPersistence implements AutoCloseable {
  private final ProjectAgentEventStore events;
  private final ProfileEventLogStore profile;
  private final AgentEventBus.Subscription subscription;
  private volatile boolean closed;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "Shared Spring persistence services are intentional; no dependency is exposed.")
  public StreamingEventPersistence(
      AgentEventBus bus, ProjectAgentEventStore events, ProfileEventLogStore profile) {
    this.events = events;
    this.profile = profile;
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
    return !closed
        && subscription != null
        && events instanceof SqliteProjectAgentEventStore
        && profile.hasManagedLog()
        && event.projectId() != null
        && (event.type() == AgentEventType.MESSAGE_DELTA
            || event.type() == AgentEventType.THINKING_DELTA);
  }

  private void persist(AgentEvent event) {
    if (handles(event) && events instanceof SqliteProjectAgentEventStore sqlite) {
      profile.persistStreamingEvent(sqlite, event);
    }
  }

  @Override
  @jakarta.annotation.PreDestroy
  public void close() {
    closed = true;
    if (subscription != null) subscription.unsubscribe();
  }
}
