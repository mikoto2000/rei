package dev.mikoto2000.rei.secretaryevents;

import dev.mikoto2000.rei.event.AgentEventBus;
import dev.mikoto2000.rei.event.AgentEventType;
import dev.mikoto2000.rei.secretary.SecretaryOrchestrator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Events only invalidate a bounded rule scan. Their text cannot become commands or permissions. */
@Component
@ConditionalOnProperty(name = "rei.secretary.enabled", havingValue = "true")
public final class SecretaryOrchestrationEvents {
  private final AgentEventBus.Subscription subscription;

  public SecretaryOrchestrationEvents(AgentEventBus events, SecretaryOrchestrator service) {
    subscription =
        events.subscribe(
            event -> {
              if (event.projectId() != null
                  && java.util.Set.of(
                          AgentEventType.AGENT_RUN_COMPLETED,
                          AgentEventType.AGENT_RUN_FAILED,
                          AgentEventType.DEPENDENCY_COMPLETED,
                          AgentEventType.DEPENDENCY_UPDATED,
                          AgentEventType.GOAL_UPDATED)
                      .contains(event.type())) service.tick();
            });
  }

  @jakarta.annotation.PreDestroy
  public void close() {
    subscription.unsubscribe();
  }
}
