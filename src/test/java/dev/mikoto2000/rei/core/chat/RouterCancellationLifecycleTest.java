package dev.mikoto2000.rei.core.chat;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mikoto2000.rei.core.service.CommandCancellationService;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class RouterCancellationLifecycleTest {
  private AgentRunContext owner(String id, String project) {
    return new AgentRunContext(id, "session-" + id, Path.of("."), project);
  }

  @Test
  void everyPublishedRunCanBeCancelledBeforeExecutorStartsAndLeavesNoFutureRequest() {
    var cancellation = new CommandCancellationService();
    var tasks = new ArrayList<Runnable>();
    var called = new AtomicBoolean();
    var router = new ConversationInputRouter(tasks::add, (c, p, q) -> called.set(true));
    router.setCancellation(cancellation);
    var accepted = new AtomicBoolean();
    router.onChange(() -> {
      if (!router.activeRuns().isEmpty()) accepted.set(cancellation.cancelProject("a"));
    });
    router.submit(owner("run", "a"), "hello");
    assertThat(accepted).isTrue();
    try {
      tasks.removeFirst().run();
      assertThat(called).isFalse();
      assertThat(router.activeRuns()).isEmpty();
      assertThat(cancellation.cancelRun("run")).isFalse();
    } finally { Thread.interrupted(); }
  }

  @Test
  void cancellingAStopsBlockingWorkAndCleanupWhileBContinues() throws Exception {
    var cancellation = new CommandCancellationService();
    var started = new CountDownLatch(2);
    var finishedA = new CountDownLatch(1);
    var releaseB = new CountDownLatch(1);
    var cleanupB = new CountDownLatch(1);
    var router = new ConversationInputRouter(work -> Thread.ofVirtual().start(work), (c, p, q) -> {
      started.countDown();
      try {
        if (c.projectId().equals("a")) new CountDownLatch(1).await();
        else releaseB.await();
      } catch (InterruptedException cancelled) { Thread.currentThread().interrupt(); }
    });
    router.setCancellation(cancellation);
    router.onCleanup(c -> { if (c.projectId().equals("a")) finishedA.countDown(); else cleanupB.countDown(); });
    router.submit(owner("a1", "a"), "A");
    router.submit(owner("b1", "b"), "B");
    try {
      assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(cancellation.cancelProject("a")).isTrue();
      assertThat(finishedA.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(router.activeRuns()).extracting(ActiveRun::runId).containsExactly("b1");
      assertThat(cancellation.cancelProject("a")).isFalse();
      assertThat(cleanupB.getCount()).isEqualTo(1);
    } finally {
      cancellation.cancelProject("a");
      releaseB.countDown();
      assertThat(cleanupB.await(5, TimeUnit.SECONDS)).isTrue();
    }
  }
}
