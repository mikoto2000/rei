package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.application.run.*;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.event.*;
import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WebBoundaryTest {
  static class TrackingBus extends InMemoryAgentEventBus {
    final AtomicInteger subscriptions = new AtomicInteger();
    @Override public Subscription subscribe(AgentEventListener listener) {
      var actual = super.subscribe(listener);
      subscriptions.incrementAndGet();
      var removed = new java.util.concurrent.atomic.AtomicBoolean();
      return () -> { if (removed.compareAndSet(false, true)) { actual.unsubscribe(); subscriptions.decrementAndGet(); } };
    }
  }
  @Test void heartbeatHasNoSequenceAndDisconnectReleasesListenerAndTimer() throws Exception {
    var bus = new TrackingBus();
    var registry = new RunRegistry(Clock.systemUTC()); registry.register(RunRegistryTest.context("run"));
    var scheduler = mock(ScheduledExecutorService.class);
    var timer = mock(ScheduledFuture.class);
    doReturn(timer).when(scheduler).scheduleAtFixedRate(any(Runnable.class), eq(20L), eq(20L), eq(TimeUnit.SECONDS));
    var beat = new CountDownLatch(1);
    var sink = new SseBridgeTest.Sink() { public void heartbeat() { beat.countDown(); } };
    try (var bridge = new SseBridge(bus, new RunService(registry), "", Executors.newVirtualThreadPerTaskExecutor(), scheduler)) {
      var connection = bridge.connect("run", sink);
      var task = org.mockito.ArgumentCaptor.forClass(Runnable.class);
      verify(scheduler).scheduleAtFixedRate(task.capture(), eq(20L), eq(20L), eq(TimeUnit.SECONDS));
      task.getValue().run();
      assertThat(beat.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(bus.lastSequence()).isZero();
      assertThat(bus.subscriptions).hasValue(1);
      connection.close(); connection.close();
      assertThat(bus.subscriptions).hasValue(0);
      verify(timer).cancel(false);
    }
  }
  @Test void sendIOExceptionUnsubscribesAndDoesNotCancelRun() throws Exception {
    var bus = new TrackingBus();
    var registry = new RunRegistry(Clock.systemUTC()); registry.register(RunRegistryTest.context("run"));
    var sink = new SseBridgeTest.Sink() {
      public void event(WebApiEventDto event) throws Exception { throw new java.io.IOException("disconnected"); }
    };
    try (var bridge = new SseBridge(bus, new RunService(registry), "")) {
      bridge.connect("run", sink);
      bus.publish(new AgentEventFactory(Clock.systemUTC()).runStarted("run", "test", null));
      assertThat(sink.ended.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(bus.subscriptions).hasValue(0);
      assertThat(registry.get("run").status()).isEqualTo(RunStatus.QUEUED);
    }
  }
  @Test void runningCancellationWaitsForActualCleanupBeforeNextProjectRun() throws Exception {
    var registry = new RunRegistry(Clock.systemUTC());
    var bus = new InMemoryAgentEventBus();
    var events = new AgentEventFactory(Clock.systemUTC());
    var cancellation = new CommandCancellationService();
    var started = new CountDownLatch(1); var cleanup = new CountDownLatch(1);
    var release = new CountDownLatch(1); var next = new CountDownLatch(1);
    var other = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var router = new ConversationInputRouter(executor, (context, prompt, input) -> {
        try (var scope = AgentRunScope.open(context)) {
          cancellation.begin(Thread.currentThread());
          try {
            if (prompt.equals("slow")) {
              started.countDown();
              try { new CountDownLatch(1).await(); }
              catch (InterruptedException expected) { /* stop requested */ }
              finally { cleanup.countDown(); await(release); }
            } else {
              if (prompt.equals("next")) next.countDown(); else other.countDown();
              bus.publish(events.runCompleted(context.runId(), 1));
            }
          } finally { cancellation.clear(); }
        }
      });
      try (var service = new RunService(registry, bus, events, cancellation, router::cancelQueued)) {
        for (String id : new String[] {"slow", "next", "other"}) {
          var context = new AgentRunContext(id, "session-" + id, Path.of("."), id.equals("other") ? "other" : "project");
          registry.register(context);
          router.submit(context, id, work -> service.execute(context, work));
        }
        try {
          assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
          assertThat(other.await(5, TimeUnit.SECONDS)).isTrue();
          assertThat(service.cancel("slow").accepted()).isTrue();
          assertThat(cleanup.await(5, TimeUnit.SECONDS)).isTrue();
          assertThat(next.getCount()).isEqualTo(1);
          assertThat(service.get("slow").status()).isEqualTo(RunStatus.CANCELLED);
        } finally { release.countDown(); }
        assertThat(next.await(5, TimeUnit.SECONDS)).isTrue();
      }
    }
  }
  private static void await(CountDownLatch latch) {
    boolean interrupted = false;
    for (;;) try { latch.await(); break; } catch (InterruptedException error) { interrupted = true; }
    if (interrupted) Thread.currentThread().interrupt();
  }
}
