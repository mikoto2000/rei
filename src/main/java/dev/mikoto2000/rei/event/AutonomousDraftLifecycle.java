package dev.mikoto2000.rei.event;

import dev.mikoto2000.rei.autonomous.AutonomousDraftStore;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** Connects application shutdown to opportunity admission, without connecting a publisher. */
@Component
public final class AutonomousDraftLifecycle implements AutoCloseable {
  private final AutonomousDraftStore store;
  private final AgentEventBus.Subscription subscription;

  @org.springframework.beans.factory.annotation.Autowired
  public AutonomousDraftLifecycle(ObjectProvider<AutonomousDraftStore> stores, AgentEventBus bus) {
    this(stores.getIfAvailable(), bus);
  }

  public AutonomousDraftLifecycle(AutonomousDraftStore store, AgentEventBus bus) {
    this.store = store;
    this.subscription =
        bus.subscribe(event -> {
          if (store != null && event.type() == AgentEventType.APPLICATION_SHUTDOWN_STARTED)
            store.stop();
        });
  }

  @Override
  @PreDestroy
  public void close() {
    if (store != null) store.stop();
    subscription.unsubscribe();
  }
}
