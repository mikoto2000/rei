package dev.mikoto2000.rei.application.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventType;
import dev.mikoto2000.rei.event.InMemoryAgentEventBus;
import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 待機中実行キャンセルの結果と状態の契約を検証するテスト。 */
class QueuedRunCancellationTest {
  /** 実行の受付と状態管理で、秘密情報の保護と利用範囲が契約に従うことを確認する。 */
  @Test
  void runnerFailurePreservesRedactedUnderlyingCause() {
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
      runs.execute(
          owner,
          () -> {
            throw new IllegalStateException(
                "operation failed", new java.io.IOException("disk full password=hidden"));
          });
      assertEquals(RunStatus.FAILED, runs.get("run").status());
      assertTrue(
          runs.get("run")
              .failure()
              .message()
              .contains("operation failed → disk full password=[REDACTED]"));
    }
  }

  /** 実行の受付と状態管理の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void terminalCompletionCannotOverwriteAcceptedCancellationOrPublishFalseSuccess() {
    var registry = new RunRegistry(Clock.systemUTC());
    var bus = new InMemoryAgentEventBus();
    var owner = new AgentRunContext("run", "session", Path.of("."), "p");
    registry.register(owner);
    var events = new java.util.ArrayList<AgentEvent>();
    bus.subscribe(events::add);
    try (var runs =
        new RunService(
            registry,
            bus,
            new AgentEventFactory(Clock.systemUTC()),
            new CommandCancellationService(),
            id -> true)) {
      runs.cancel("run");
      runs.finishMissingTerminal(owner, RunStatus.COMPLETED);
      assertEquals(RunStatus.CANCELLED, runs.get("run").status());
      assertTrue(
          events.stream().noneMatch(event -> event.type() == AgentEventType.AGENT_RUN_COMPLETED));
    }
  }

  /** 実行の受付と状態管理の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void cancellationAfterQueueDispatchButBeforeLifecycleStartsNotifiesWithoutRunningWork() {
    var registry = new RunRegistry(Clock.systemUTC());
    var bus = new InMemoryAgentEventBus();
    var calls = new AtomicInteger();
    var work = new AtomicInteger();
    var owner = new AgentRunContext("run", "session", Path.of("."), "p");
    registry.register(owner);
    try (var runs =
        new RunService(
            registry,
            bus,
            new AgentEventFactory(Clock.systemUTC()),
            new CommandCancellationService(),
            id -> false)) {
      runs.onQueuedCancellation(
          "run",
          () -> {
            assertFalse(Thread.holdsLock(bus));
            calls.incrementAndGet();
          });
      runs.cancel("run");
      runs.execute(owner, work::incrementAndGet);
      runs.execute(owner, work::incrementAndGet);
      assertEquals(0, work.get());
      assertEquals(1, calls.get());
    }
  }

  /** 実行の受付と状態管理の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void explicitQueuedCancellationNotifiesOnceAfterTerminalStateIsPublished() {
    var registry = new RunRegistry(Clock.systemUTC());
    var bus = new InMemoryAgentEventBus();
    var calls = new AtomicInteger();
    var owner = new AgentRunContext("run", "session", Path.of("."), "p");
    registry.register(owner);
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
            assertFalse(Thread.holdsLock(bus));
            assertEquals(RunStatus.CANCELLED, runs.get("run").status());
            calls.incrementAndGet();
          });
      assertTrue(runs.cancel("run").accepted());
      assertFalse(runs.cancel("run").accepted());
      assertEquals(1, calls.get());
    }
  }
}
