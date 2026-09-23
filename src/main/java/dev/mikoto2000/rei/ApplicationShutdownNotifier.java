package dev.mikoto2000.rei;

import dev.mikoto2000.rei.event.AgentEventBus;
import dev.mikoto2000.rei.event.AgentEventFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

/** Announces shutdown before lifecycle stop and bean destruction disconnect consumers. */
@Component
public final class ApplicationShutdownNotifier implements ApplicationListener<ContextClosedEvent>, Ordered {
  private final AgentEventBus bus;
  private final AgentEventFactory events;
  private final ApplicationContext context;
  private boolean announced;

  public ApplicationShutdownNotifier(AgentEventBus bus, AgentEventFactory events, ApplicationContext context) {
    this.bus = bus;
    this.events = events;
    this.context = context;
  }

  public synchronized void begin(String reason) {
    if (announced) return;
    announced = true;
    bus.publish(events.applicationShutdownStarted(reason));
  }

  @Override public void onApplicationEvent(ContextClosedEvent event) {
    if (event.getApplicationContext() == context) begin("context_closed");
  }

  @Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE; }
}
