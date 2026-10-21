package dev.mikoto2000.rei.application.run;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.InMemoryAgentEventBus;
import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 待機中限定実行キャンセルの結果と状態の契約を検証するテスト。 */
class PendingOnlyRunCancellationTest {
  /** 実行の受付と状態管理の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void alreadyDispatchedWorkIsNotCancelledByPendingOnlyRequest() {
    var registry = new RunRegistry(Clock.systemUTC());
    var bus = new InMemoryAgentEventBus();
    var owner = new AgentRunContext("run", "session", Path.of("."), "p");
    registry.register(owner);
    try (var runs =
        new RunService(
            registry,
            bus,
            new AgentEventFactory(Clock.systemUTC()),
            new CommandCancellationService(),
            id -> false)) {
      assertThat(runs.cancelQueuedOnly("run").accepted()).isFalse();
      assertThat(runs.get("run").status()).isEqualTo(RunStatus.QUEUED);
      registry.transition("run", RunStatus.RUNNING, null);
      assertThat(runs.cancelQueuedOnly("run").accepted()).isFalse();
      assertThat(runs.get("run").status()).isEqualTo(RunStatus.RUNNING);
    }
  }

  /** 実行の受付と状態管理の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void queuedOnlyCancellationPublishesTerminalAndNotifiesOnceOutsideBusLock() {
    var registry = new RunRegistry(Clock.systemUTC());
    var bus = new InMemoryAgentEventBus();
    var owner = new AgentRunContext("run", "session", Path.of("."), "p");
    registry.register(owner);
    var calls = new AtomicInteger();
    try (var runs =
        new RunService(
            registry,
            bus,
            new AgentEventFactory(Clock.systemUTC()),
            new CommandCancellationService(),
            id -> true)) {
      runs.onQueuedCancellation(
          "run",
          () -> {
            assertThat(Thread.holdsLock(bus)).isFalse();
            calls.incrementAndGet();
          });
      assertThat(runs.cancelQueuedOnly("run").accepted()).isTrue();
      assertThat(runs.get("run").status()).isEqualTo(RunStatus.CANCELLED);
      assertThat(runs.cancelQueuedOnly("run").accepted()).isFalse();
      assertThat(calls.get()).isEqualTo(1);
    }
  }
}
