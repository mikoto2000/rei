package dev.mikoto2000.rei.event;

import org.springframework.stereotype.Component;

@Component
public class ProjectAgentEventSubscriber {
  private final AgentEventBus.Subscription subscription;
  public ProjectAgentEventSubscriber(AgentEventBus bus, ProjectAgentEventStore store) {
    subscription = bus.subscribe(store);
  }
  @jakarta.annotation.PreDestroy public void close() { subscription.unsubscribe(); }
}
