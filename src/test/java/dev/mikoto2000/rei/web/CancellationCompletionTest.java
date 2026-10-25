package dev.mikoto2000.rei.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mikoto2000.rei.application.run.RunRegistry;
import dev.mikoto2000.rei.application.run.RunService;
import dev.mikoto2000.rei.application.run.RunStatus;
import dev.mikoto2000.rei.core.chat.ConversationInputRouter;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.InMemoryAgentEventBus;
import java.time.Clock;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class CancellationCompletionTest {
  @Test
  void requestIsNotTerminalUntilNonCooperativeRunnerActuallyReturns() throws Exception {
    var cancellation = new CommandCancellationService();
    var registry = new RunRegistry(Clock.systemUTC());
    var bus = new InMemoryAgentEventBus();
    var events = new AgentEventFactory(Clock.systemUTC());
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var done = new CountDownLatch(1);
    var router = new ConversationInputRouter(work -> Thread.ofVirtual().start(work), (c, p, q) -> {
      started.countDown();
      boolean interrupted = false;
      while (true) {
        try { release.await(); break; }
        catch (InterruptedException stop) { interrupted = true; }
      }
      if (interrupted) Thread.currentThread().interrupt();
    });
    router.setCancellation(cancellation);
    router.onCleanup(c -> done.countDown());
    try (var service = new RunService(registry, bus, events, cancellation, router::cancelQueued)) {
      var owner = RunRegistryTest.context("run");
      registry.register(owner);
      router.submit(owner, "hello", work -> service.execute(owner, work));
      try {
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(service.cancel("run").accepted()).isTrue();
        assertThat(service.get("run").status()).isEqualTo(RunStatus.RUNNING);
        assertThat(service.get("run").completedAt()).isNull();
        assertThat(service.cancel("run").accepted()).isFalse();
        assertThat(router.activeRuns()).hasSize(1);
      } finally { release.countDown(); }
      assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(service.get("run").status()).isEqualTo(RunStatus.CANCELLED);
      assertThat(router.activeRuns()).isEmpty();
      assertThat(service.cancel("run").accepted()).isFalse();
    }
  }
}
