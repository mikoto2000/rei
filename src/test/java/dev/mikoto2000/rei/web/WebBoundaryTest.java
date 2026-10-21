package dev.mikoto2000.rei.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import dev.mikoto2000.rei.application.run.RunRegistry;
import dev.mikoto2000.rei.application.run.RunService;
import dev.mikoto2000.rei.application.run.RunStatus;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.AgentRunScope;
import dev.mikoto2000.rei.core.chat.ConversationInputRouter;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventListener;
import dev.mikoto2000.rei.event.InMemoryAgentEventBus;
import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Web境界条件の結果と状態の契約を検証するテスト。 */
class WebBoundaryTest {
  /** 検証条件を再現するためのTracking通知の連携先。 */
  static class TrackingBus extends InMemoryAgentEventBus {
    /** 検証に使う購読一覧。 */
    final AtomicInteger subscriptions = new AtomicInteger();

    /** {@code subscribe} の条件を再現するための補助情報を返す。 */
    @Override
    public Subscription subscribe(AgentEventListener listener) {
      var actual = super.subscribe(listener);
      subscriptions.incrementAndGet();
      var removed = new java.util.concurrent.atomic.AtomicBoolean();
      return () -> {
        if (removed.compareAndSet(false, true)) {
          actual.unsubscribe();
          subscriptions.decrementAndGet();
        }
      };
    }
  }

  /** 生存確認保有なし発生順序および切断について、通知の受け取り先を解放することを確認する。 */
  @Test
  void heartbeatHasNoSequenceAndDisconnectReleasesListenerAndTimer() throws Exception {
    var bus = new TrackingBus();
    var registry = new RunRegistry(Clock.systemUTC());
    registry.register(RunRegistryTest.context("run"));
    var scheduler = mock(ScheduledExecutorService.class);
    var timer = mock(ScheduledFuture.class);
    doReturn(timer)
        .when(scheduler)
        .scheduleAtFixedRate(any(Runnable.class), eq(20L), eq(20L), eq(TimeUnit.SECONDS));
    var beat = new CountDownLatch(1);
    var sink =
        new SseBridgeTest.Sink() {
          /** {@code heartbeat} の条件を再現するため、検証用の操作や状態を扱う。 */
          public void heartbeat() {
            beat.countDown();
          }
        };
    try (var bridge =
        new SseBridge(
            bus,
            new RunService(registry),
            "",
            Executors.newVirtualThreadPerTaskExecutor(),
            scheduler)) {
      assertThat(bus.subscriptions)
          .hasValue(1); // Application shutdown listener belongs to the bridge.
      var connection = bridge.connect("run", sink);
      var task = org.mockito.ArgumentCaptor.forClass(Runnable.class);
      verify(scheduler).scheduleAtFixedRate(task.capture(), eq(20L), eq(20L), eq(TimeUnit.SECONDS));
      task.getValue().run();
      assertThat(beat.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(bus.lastSequence()).isZero();
      assertThat(bus.subscriptions).hasValue(2);
      connection.close();
      connection.close();
      assertThat(bus.subscriptions).hasValue(1);
      verify(timer).cancel(false);
    }
    assertThat(bus.subscriptions).hasValue(0);
  }

  /** Web APIによる操作の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void sendIOExceptionUnsubscribesAndDoesNotCancelRun() throws Exception {
    var bus = new TrackingBus();
    var registry = new RunRegistry(Clock.systemUTC());
    registry.register(RunRegistryTest.context("run"));
    var sink =
        new SseBridgeTest.Sink() {
          /** 検証で使用するイベントを、指定された条件に合わせて用意する。 */
          public void event(WebApiEventDto event) throws Exception {
            throw new java.io.IOException("disconnected");
          }
        };
    try (var bridge = new SseBridge(bus, new RunService(registry), "")) {
      bridge.connect("run", sink);
      bus.publish(new AgentEventFactory(Clock.systemUTC()).runStarted("run", "test", null));
      assertThat(sink.ended.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(bus.subscriptions).hasValue(1); // Only the bridge's shutdown listener remains.
      assertThat(registry.get("run").status()).isEqualTo(RunStatus.QUEUED);
    }
    assertThat(bus.subscriptions).hasValue(0);
  }

  /** Web APIによる操作の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void runningCancellationWaitsForActualCleanupBeforeNextProjectRun() throws Exception {
    var registry = new RunRegistry(Clock.systemUTC());
    var bus = new InMemoryAgentEventBus();
    var events = new AgentEventFactory(Clock.systemUTC());
    var cancellation = new CommandCancellationService();
    var started = new CountDownLatch(1);
    var cleanup = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var next = new CountDownLatch(1);
    var other = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var router =
          new ConversationInputRouter(
              executor,
              (context, prompt, input) -> {
                try (var scope = AgentRunScope.open(context)) {
                  cancellation.begin(Thread.currentThread());
                  try {
                    if (prompt.equals("slow")) {
                      started.countDown();
                      try {
                        new CountDownLatch(1).await();
                      } catch (InterruptedException expected) {
                        /* stop requested */
                      } finally {
                        cleanup.countDown();
                        await(release);
                      }
                    } else {
                      if (prompt.equals("next")) next.countDown();
                      else other.countDown();
                      bus.publish(events.runCompleted(context.runId(), 1));
                    }
                  } finally {
                    cancellation.clear();
                  }
                }
              });
      try (var service =
          new RunService(registry, bus, events, cancellation, router::cancelQueued)) {
        for (String id : new String[] {"slow", "next", "other"}) {
          var context =
              new AgentRunContext(
                  id, "session-" + id, Path.of("."), id.equals("other") ? "other" : "project");
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
        } finally {
          release.countDown();
        }
        assertThat(next.await(5, TimeUnit.SECONDS)).isTrue();
      }
    }
  }

  /** 検証用に、必要な状態が整ってから先へ進むため、Web境界条件検証を待つ。 */
  private static void await(CountDownLatch latch) {
    boolean interrupted = false;
    for (; ; )
      try {
        latch.await();
        break;
      } catch (InterruptedException error) {
        interrupted = true;
      }
    if (interrupted) Thread.currentThread().interrupt();
  }
}
