package dev.mikoto2000.rei.ui.shell;

import java.util.concurrent.atomic.AtomicBoolean;

import dev.mikoto2000.rei.event.AgentEventBus;
import dev.mikoto2000.rei.event.AgentEventListener;

/** Owns the Shell listener subscription and its temporary TUI exclusion. */
public final class ShellEventSession implements AutoCloseable {
  private final AtomicBoolean active = new AtomicBoolean(true);
  private final AgentEventBus.Subscription subscription;

  public ShellEventSession(AgentEventBus bus, AgentEventListener listener) {
    subscription = bus.subscribe(event -> {
      if (active.get()) listener.onEvent(event);
    });
  }

  public void pause() { active.set(false); }
  public void resume() { active.set(true); }

  @Override
  public void close() {
    active.set(false);
    subscription.unsubscribe();
  }
}
