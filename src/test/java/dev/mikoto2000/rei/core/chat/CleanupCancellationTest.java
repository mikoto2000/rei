package dev.mikoto2000.rei.core.chat;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mikoto2000.rei.core.service.CommandCancellationService;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class CleanupCancellationTest {
  @Test
  void resourceCleanupRemainsVisibleAndCancellableUntilItActuallyReturns() throws Exception {
    var cancellation = new CommandCancellationService();
    var disposing = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var finished = new CountDownLatch(1);
    var router =
        new ConversationInputRouter(
            work -> Thread.ofVirtual().start(work),
            (c, p, q) -> {
              cancellation.register(
                  () -> {
                    disposing.countDown();
                    boolean interrupted = false;
                    while (true) {
                      try {
                        release.await();
                        break;
                      } catch (InterruptedException stop) {
                        interrupted = true;
                      }
                    }
                    if (interrupted) Thread.currentThread().interrupt();
                  });
            });
    router.setCancellation(cancellation);
    router.onCleanup(c -> finished.countDown());
    router.submit(new AgentRunContext("cleanup", "session", Path.of("."), "project"), "work");
    try {
      assertThat(disposing.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(router.activeRuns()).hasSize(1);
      assertThat(cancellation.cancelProject("project")).isTrue();
      assertThat(cancellation.diagnostic("cleanup").finishedAt()).isNull();
    } finally {
      release.countDown();
    }
    assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(router.activeRuns()).isEmpty();
    assertThat(cancellation.diagnostic("cleanup").status()).isEqualTo("FINISHED");
  }
}
