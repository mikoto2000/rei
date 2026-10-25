package dev.mikoto2000.rei.secretaryevents;

import dev.mikoto2000.rei.event.AgentEventBus;
import dev.mikoto2000.rei.event.DependencyStatusPayload;
import dev.mikoto2000.rei.secretary.SecretaryFollowups;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Consumes already-owned dependency facts. It never probes networks or interprets text. */
@Component
@ConditionalOnProperty(name = "rei.secretary.enabled", havingValue = "true")
public final class SecretaryDependencyIntake {
  private final AgentEventBus.Subscription subscription;

  public SecretaryDependencyIntake(AgentEventBus bus, SecretaryFollowups service) {
    subscription =
        bus.subscribe(
            event -> {
              if (event.projectId() != null
                  && java.util.Set.of(
                          dev.mikoto2000.rei.event.AgentEventType.DEPENDENCY_UPDATED,
                          dev.mikoto2000.rei.event.AgentEventType.DEPENDENCY_COMPLETED,
                          dev.mikoto2000.rei.event.AgentEventType.DEPENDENCY_FAILED,
                          dev.mikoto2000.rei.event.AgentEventType.DEPENDENCY_CANCELLED)
                      .contains(event.type())
                  && event.payload() instanceof DependencyStatusPayload fact) {
                try {
                  service.observeDependency(
                      event.projectId(), fact.dependencyId(), fact.state(), fact.revision());
                } catch (RuntimeException failure) {
                  org.slf4j.LoggerFactory.getLogger(getClass())
                      .warn(
                          "Secretary dependency observation failed: {}",
                          failure.getClass().getSimpleName());
                }
              }
            });
  }

  @jakarta.annotation.PreDestroy
  public void close() {
    subscription.unsubscribe();
  }
}
