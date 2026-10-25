package dev.mikoto2000.rei.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.AgentRunScope;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class CancellationRaceTest {
  private AgentRunContext owner(String id) {
    return new AgentRunContext(id, "session", Path.of("."), "project");
  }

  @Test
  void startAndCancellationRaceNeverLoseTheRequest() throws Exception {
    var service = new CommandCancellationService();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < 50; i++) {
        var owner = owner("start-" + i);
        var barrier = new CyclicBarrier(2);
        var start =
            executor.submit(
                () -> {
                  barrier.await();
                  service.prepare(owner);
                  return null;
                });
        var stop =
            executor.submit(
                () -> {
                  barrier.await();
                  return service.cancelRun(owner.runId());
                });
        start.get(5, TimeUnit.SECONDS);
        assertThat(stop.get(5, TimeUnit.SECONDS)).isTrue();
        try (var scope = AgentRunScope.open(owner)) {
          service.begin(mock(Thread.class));
          assertThat(service.isCancellationRequested()).isTrue();
          assertThat(service.consumeCancellationRequested()).isTrue();
          assertThat(service.isCancellationRequested()).isTrue();
          service.clear();
        }
        service.finish(owner.runId());
        assertThat(service.cancelRun(owner.runId())).isFalse();
      }
    }
  }

  @Test
  void finishAndChildRegistrationRacesAreIdempotentAndLeaveNoFutureStop() throws Exception {
    var service = new CommandCancellationService();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < 50; i++) {
        var owner = owner("end-" + i);
        service.prepare(owner);
        var calls = new AtomicInteger();
        var barrier = new CyclicBarrier(2);
        var registration =
            executor.submit(
                () -> {
                  barrier.await();
                  service.onCancel(owner.runId(), calls::incrementAndGet);
                  return null;
                });
        var stop =
            executor.submit(
                () -> {
                  barrier.await();
                  return service.cancelRun(owner.runId());
                });
        registration.get(5, TimeUnit.SECONDS);
        assertThat(stop.get(5, TimeUnit.SECONDS)).isTrue();
        assertThat(calls).hasValue(1);
        var endBarrier = new CyclicBarrier(2);
        var finish =
            executor.submit(
                () -> {
                  endBarrier.await();
                  service.finish(owner.runId());
                  return null;
                });
        var repeat =
            executor.submit(
                () -> {
                  endBarrier.await();
                  return service.cancelRegisteredRun(owner.runId());
                });
        finish.get(5, TimeUnit.SECONDS);
        assertThat(repeat.get(5, TimeUnit.SECONDS)).isFalse();
        assertThat(calls).hasValue(1);
        assertThat(service.cancelRun(owner.runId())).isFalse();
        assertThat(service.diagnostic(owner.runId()).status()).isEqualTo("FINISHED");
      }
    }
  }

  @Test
  void firstStopRacingWithFinishNeverInterruptsAnEndedRunOrLeavesPendingState() throws Exception {
    var service = new CommandCancellationService();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < 50; i++) {
        var owner = owner("first-stop-" + i);
        service.prepare(owner);
        var barrier = new CyclicBarrier(2);
        var end =
            executor.submit(
                () -> {
                  barrier.await();
                  service.finish(owner.runId());
                  return null;
                });
        var stop =
            executor.submit(
                () -> {
                  barrier.await();
                  return service.cancelRun(owner.runId());
                });
        end.get(5, TimeUnit.SECONDS);
        boolean accepted = stop.get(5, TimeUnit.SECONDS);
        var diagnostic = service.diagnostic(owner.runId());
        assertThat(diagnostic.status()).isEqualTo("FINISHED");
        assertThat(diagnostic.requestedAt() != null).isEqualTo(accepted);
        assertThat(service.cancelRun(owner.runId())).isFalse();
      }
    }
  }

  @Test
  void stalledAndFailedSignalsStayDiagnosableWithoutLoggingExceptionMessages() {
    var now = new AtomicReference<>(Instant.parse("2026-10-25T00:00:00Z"));
    var clock =
        new Clock() {
          public ZoneId getZone() {
            return ZoneOffset.UTC;
          }

          public Clock withZone(ZoneId zone) {
            return this;
          }

          public Instant instant() {
            return now.get();
          }
        };
    var service = new CommandCancellationService(clock);
    service.prepare(owner("stalled"));
    assertThat(service.cancelProject("project")).isTrue();
    assertThat(service.diagnostic("stalled").status()).isEqualTo("CANCEL_REQUESTED");
    now.set(now.get().plusSeconds(31));
    assertThat(service.diagnostic("stalled").status()).isEqualTo("CANCEL_STALLED");
    assertThat(service.diagnostic("stalled").finishedAt()).isNull();
    service.prepare(owner("failed"));
    service.onCancel(
        "failed",
        () -> {
          throw new IllegalStateException("token=PRIVATE");
        });
    assertThat(service.cancelRegisteredRun("failed")).isTrue();
    assertThat(service.diagnostic("failed").status()).isEqualTo("CANCEL_FAILED");
    assertThat(service.diagnostic("failed").failure()).isEqualTo("IllegalStateException");
    assertThat(service.diagnostic("failed").toString()).doesNotContain("PRIVATE");
    service.finish("stalled");
    service.finish("failed");
  }
}
