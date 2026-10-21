package dev.mikoto2000.rei.timing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventType;
import dev.mikoto2000.rei.event.InMemoryAgentEventBus;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** 時間計測補助エージェント受付から終了までの管理の結果と状態の契約を検証するテスト。 */
class TimingSubAgentLifecycleTest {
  /** 既存の受付から終了までの管理を利用することを確認する。 */
  @Test
  void independentChildRunUsesExistingLifecycleWithoutKeepingTaskText() {
    var store =
        new TimingStore(
            true, 10, 20, 100, Duration.ofHours(1), Clock.systemUTC(), System::nanoTime);
    var bus = new InMemoryAgentEventBus();
    var factory = new AgentEventFactory(Clock.systemUTC());
    var child = new AgentRunContext("child", "subagent:child", Path.of("."), "project");
    try (var observer = new TimingEventObserver(store, bus)) {
      bus.publish(
          factory
              .subAgentLifecycle(
                  AgentEventType.SUBAGENT_STARTED,
                  "parent",
                  "child",
                  "worker",
                  "TASK-SECRET",
                  "RUNNING",
                  0,
                  null)
              .withOwnership(child));
      assertTrue(store.snapshot("project", "subagent:child", "child").isPresent());
      assertTrue(store.beginSpan("child", "call", null, null, null, TimingRecorder.Category.LLM));
      store.endSpan("child", "call", TimingRecorder.Status.SUCCESS);
      bus.publish(
          factory
              .subAgentLifecycle(
                  AgentEventType.SUBAGENT_FAILED,
                  "parent",
                  "child",
                  "worker",
                  "TASK-SECRET",
                  "CANCELLED",
                  1,
                  null)
              .withOwnership(child));
      var run = store.snapshot("project", "subagent:child", "child").orElseThrow();
      assertEquals(TimingRecorder.Status.CANCELLED, run.status());
      assertFalse(run.toString().contains("SECRET"));
      assertTrue(store.snapshot("project", "wrong-session", "child").isEmpty());
    }
  }
}
