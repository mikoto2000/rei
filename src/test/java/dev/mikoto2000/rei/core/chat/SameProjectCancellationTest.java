package dev.mikoto2000.rei.core.chat;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mikoto2000.rei.core.service.CommandCancellationService;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SameProjectCancellationTest {
  @Test
  void projectCancellationStopsAllConcurrentReadRunsAndLeavesOtherProjectAlone() {
    var cancellation = new CommandCancellationService();
    var tasks = new ArrayList<Runnable>();
    var executed = new AtomicInteger();
    var router = new ConversationInputRouter(tasks::add, (c, p, q) -> executed.incrementAndGet());
    router.setCancellation(cancellation);
    for (String id : java.util.List.of("a1", "a2", "b")) {
      var owner =
          new AgentRunContext(
              id,
              "session-" + id,
              Path.of("."),
              id.startsWith("a") ? "a" : "b",
              AgentRunContext.RequestSource.SHELL,
              AgentRunContext.Mode.READ_ONLY);
      router.submit(owner, "work");
    }
    assertThat(router.activeRuns()).hasSize(3);
    assertThat(cancellation.cancelProject("a")).isTrue();
    assertThat(cancellation.cancelProject("a")).isFalse();
    for (int i = 0; i < 2; i++) {
      try {
        tasks.get(i).run();
      } finally {
        Thread.interrupted();
      }
    }
    assertThat(router.activeRuns()).extracting(ActiveRun::runId).containsExactly("b");
    assertThat(executed).hasValue(0);
    tasks.get(2).run();
    assertThat(executed).hasValue(1);
    assertThat(router.activeRuns()).isEmpty();
  }
}
