package dev.mikoto2000.rei.autonomous;

import dev.mikoto2000.rei.event.AgentEventBus;
import dev.mikoto2000.rei.event.AgentEventType;

/** Connects application shutdown to opportunity admission, without connecting a publisher. */
final class AutonomousDraftLifecycle implements AutoCloseable {
  private final AutonomousDraftStore store;
  private final AgentEventBus.Subscription subscription;

  AutonomousDraftLifecycle(AutonomousDraftStore store, AgentEventBus bus) {
    this.store = store;
    this.subscription =
        bus.subscribe(event -> {
          if (event.type() == AgentEventType.APPLICATION_SHUTDOWN_STARTED) store.stop();
        });
  }

  @Override
  public void close() {
    store.stop();
    subscription.unsubscribe();
  }
}
