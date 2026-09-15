package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.core.chat.AgentRunScope;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.event.*;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class RunCancellationTest {
  @Test void cancelBeforeBeginSurvivesAndStopsResourcesForOnlyThatRun() {
    var service = new CommandCancellationService();
    var thread = new Thread();
    var child = new AtomicInteger();
    var disposed = new AtomicInteger();
    service.cancelRun("one");
    try (var scope = AgentRunScope.open(RunRegistryTest.context("one"))) {
      service.begin(thread);
      assertThat(service.isCancellationRequested()).isTrue();
      service.onCancel("one", child::incrementAndGet);
      service.register(disposed::incrementAndGet);
      assertThat(child).hasValue(1);
      assertThat(disposed).hasValue(1);
      assertThat(thread.isInterrupted()).isTrue();
      service.clear();
    }
    try (var scope = AgentRunScope.open(RunRegistryTest.context("two"))) {
      service.begin(new Thread());
      assertThat(service.isCancellationRequested()).isFalse();
      service.clear();
    }
  }

  @Test void cancellationFactoryKeepsRunOwnershipAndTurnIdentity() {
    var event = new AgentEventFactory(Clock.systemUTC()).runCancelled("one", null)
        .withOwnership(RunRegistryTest.context("one"));
    assertThat(event.type()).isEqualTo(AgentEventType.AGENT_RUN_CANCELLED);
    assertThat(event.runId()).isEqualTo("one");
    assertThat(event.turnId()).isEqualTo("one");
    assertThat(event.sessionId()).isEqualTo("session");
    assertThat(event.projectId()).isEqualTo("project");
  }
}
