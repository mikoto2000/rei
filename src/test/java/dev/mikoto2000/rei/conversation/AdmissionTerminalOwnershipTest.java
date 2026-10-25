package dev.mikoto2000.rei.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mikoto2000.rei.application.run.RunRegistry;
import dev.mikoto2000.rei.application.run.RunService;
import dev.mikoto2000.rei.application.run.RunStatus;
import dev.mikoto2000.rei.application.session.SessionMetadata;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.ConversationInputRouter;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.InMemoryAgentEventBus;
import dev.mikoto2000.rei.storage.StorageMigrationCoordinator;
import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;

@Tag("integration")
class AdmissionTerminalOwnershipTest {
  @TempDir Path root;

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void durableEventObserverCannotFinalizeAnExecutionThatStillOwnsCleanup(boolean cancel)
      throws Exception {
    var clock = Clock.systemUTC();
    var bus = new InMemoryAgentEventBus();
    var events = new AgentEventFactory(clock);
    var cancellation = new CommandCancellationService();
    var disposing = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var finished = new CountDownLatch(1);
    try (var migrations = new StorageMigrationCoordinator(root)) {
      migrations.prepare();
      try (var admissions = new ConversationAdmissionStore(root, clock)) {
        admissions.observe(bus);
        var owner = new AgentRunContext("run", "session", root, "project");
        admissions.accept(
            new SessionMetadata("session", "project", "title", clock.instant(), clock.instant()),
            owner,
            null,
            null);
        var registry = new RunRegistry(clock);
        registry.setAdmissions(admissions);
        registry.register(owner);
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
                  bus.publish(events.runCompleted(c.runId(), 0).withOwnership(c));
                });
        router.setCancellation(cancellation);
        router.setAdmissions(admissions);
        router.onCleanup(c -> finished.countDown());
        try (var runs = new RunService(registry, bus, events, cancellation, router::cancelQueued)) {
          router.submit(owner, "work", work -> runs.execute(owner, work));
          try {
            assertThat(disposing.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(runs.get(owner.runId()).status()).isEqualTo(RunStatus.RUNNING);
            if (cancel) assertThat(runs.cancel(owner.runId()).accepted()).isTrue();
            assertThat(runs.get(owner.runId()).completedAt()).isNull();
          } finally {
            release.countDown();
          }
          assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue();
          assertThat(runs.get(owner.runId()).status())
              .isEqualTo(cancel ? RunStatus.CANCELLED : RunStatus.COMPLETED);
        }
      }
    }
  }
}
