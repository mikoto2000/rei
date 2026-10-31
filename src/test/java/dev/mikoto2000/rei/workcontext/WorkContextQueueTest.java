package dev.mikoto2000.rei.workcontext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.event.AgentEventType;
import dev.mikoto2000.rei.event.InMemoryAgentEventBus;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class WorkContextQueueTest {
  @Test
  void queuedSameProjectRunsCoalesceAndOtherProjectDoesNotWait() throws Exception {
    exercise(false);
  }

  @Test
  void oversizedCombinedInputFallsBackWithoutDroppingRunEvidence() throws Exception {
    exercise(true);
  }

  private void exercise(boolean split) throws Exception {
    var service = mock(WorkContextService.class);
    var bus = new InMemoryAgentEventBus();
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var other = new CountDownLatch(1);
    var completed = new CountDownLatch(4);
    bus.subscribe(
        e -> {
          if (e.type() == AgentEventType.WORK_CONTEXT_UPDATED) completed.countDown();
        });
    doAnswer(
            call -> {
              entered.countDown();
              assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
              return Optional.empty();
            })
        .when(service)
        .update("session-a", "r1");
    doAnswer(
            call -> {
              other.countDown();
              return Optional.empty();
            })
        .when(service)
        .update("session-b", "rb");
    if (split) {
      when(service.updateRuns(eq("session-a"), anySet()))
          .thenThrow(
              new WorkContextExtractionException(
                  WorkContextExtractionException.Reason.INPUT_TOO_LARGE));
      when(service.update("session-a", "r2")).thenReturn(Optional.empty());
      when(service.update("session-a", "r3")).thenReturn(Optional.empty());
    } else when(service.updateRuns(eq("session-a"), anySet())).thenReturn(Optional.empty());
    var properties = new WorkContextProperties(true, true, 1200, 12000, 2, 20);
    try (var automation = new WorkContextAutomation(service, properties, bus)) {
      automation.afterTerminal(new AgentRunContext("r1", "session-a", Path.of("."), "a"));
      assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
      try {
        automation.afterTerminal(new AgentRunContext("r2", "session-a", Path.of("."), "a"));
        automation.afterTerminal(new AgentRunContext("r3", "session-a", Path.of("."), "a"));
        automation.afterTerminal(new AgentRunContext("rb", "session-b", Path.of("."), "b"));
        assertThat(other.await(2, TimeUnit.SECONDS)).isTrue();
      } finally {
        release.countDown();
      }
      assertThat(completed.await(5, TimeUnit.SECONDS)).isTrue();
      verify(service).updateRuns("session-a", Set.of("r2", "r3"));
      if (split) {
        verify(service).update("session-a", "r2");
        verify(service).update("session-a", "r3");
      }
    }
  }
}
