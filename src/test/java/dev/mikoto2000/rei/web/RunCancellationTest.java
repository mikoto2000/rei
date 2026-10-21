package dev.mikoto2000.rei.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mikoto2000.rei.core.chat.AgentRunScope;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventType;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 実行キャンセルの結果と状態の契約を検証するテスト。 */
class RunCancellationTest {
  /** Web APIによる操作の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void cancelBeforeBeginSurvivesAndStopsResourcesForOnlyThatRun() {
    var service = new CommandCancellationService();
    var thread = new Thread();
    var child = new AtomicInteger();
    var disposed = new AtomicInteger();
    service.cancelRun("one");
    try (var scope = AgentRunScope.open(RunRegistryTest.context("one"))) {
      service.begin(thread);
      assertThat(service.isCancellationRequested()).isTrue();
      service.onCancel("one", child::incrementAndGet);
      service.register(disposed::incrementAndGet);
      assertThat(child).hasValue(1);
      assertThat(disposed).hasValue(1);
      assertThat(thread.isInterrupted()).isTrue();
      service.clear();
    }
    try (var scope = AgentRunScope.open(RunRegistryTest.context("two"))) {
      service.begin(new Thread());
      assertThat(service.isCancellationRequested()).isFalse();
      service.clear();
    }
  }

  /** Web APIによる操作の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void cancellationFactoryKeepsRunOwnershipAndTurnIdentity() {
    var event =
        new AgentEventFactory(Clock.systemUTC())
            .runCancelled("one", null)
            .withOwnership(RunRegistryTest.context("one"));
    assertThat(event.type()).isEqualTo(AgentEventType.AGENT_RUN_CANCELLED);
    assertThat(event.runId()).isEqualTo("one");
    assertThat(event.turnId()).isEqualTo("one");
    assertThat(event.sessionId()).isEqualTo("session");
    assertThat(event.projectId()).isEqualTo("project");
  }
}
