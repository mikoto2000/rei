package dev.mikoto2000.rei.ui.shell;

import dev.mikoto2000.rei.event.AgentEventBus;
import dev.mikoto2000.rei.event.AgentEventListener;

/** Owns the Shell listener subscription. */
public final class ShellEventSession implements AutoCloseable {
  private final AgentEventBus.Subscription subscription;

  public ShellEventSession(AgentEventBus bus, AgentEventListener listener) {
    subscription = bus.subscribe(listener);
  }

  @Override
  public void close() {
    subscription.unsubscribe();
  }
}
