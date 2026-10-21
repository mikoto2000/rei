package dev.mikoto2000.rei.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventType;
import dev.mikoto2000.rei.event.AttentionRequiredPayload;
import dev.mikoto2000.rei.event.ExecutionProgressPayload;
import dev.mikoto2000.rei.event.GoalLifecyclePayload;
import java.time.Clock;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** WebAPI注意イベントの結果と状態の契約を検証するテスト。 */
class WebApiAttentionEventTest {
  /** Web APIによる操作で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void goalProjectionIncludesStateAndBudgetsWithoutInternalReason() {
    var event =
        new AgentEvent(
            "id",
            0,
            Instant.EPOCH,
            AgentEventType.GOAL_UPDATED,
            1,
            "session",
            null,
            "run",
            null,
            null,
            new GoalLifecyclePayload("goal", "BLOCKED", 3, 3, 5, 20, "private reason"),
            "project");
    var dto = WebApiEventMapper.from(event, false, "api-secret");
    assertEquals("goal.updated", dto.type());
    assertEquals("goal", dto.payload().get("goalId"));
    assertEquals("BLOCKED", dto.payload().get("status"));
    assertEquals(5, dto.payload().get("llmCallsUsed"));
    assertFalse(dto.payload().containsKey("reason"));
  }

  /** Web APIによる操作で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void attentionAndVerifiedWaitingHaveExplicitPublicFields() {
    var event =
        new AgentEvent(
            "id",
            0,
            Instant.EPOCH,
            AgentEventType.ATTENTION_REQUIRED,
            1,
            "session",
            null,
            "run",
            null,
            null,
            new AttentionRequiredPayload("attention", "APPROVAL_REQUIRED", "Review approval"),
            "project");
    var dto = WebApiEventMapper.from(event, false, "api-secret");
    assertEquals("attention.required", dto.type());
    assertEquals("attention", dto.payload().get("attentionId"));
    assertEquals("APPROVAL_REQUIRED", dto.payload().get("kind"));
    assertEquals("Review approval", dto.payload().get("message"));
    var waiting =
        new AgentEventFactory(Clock.systemUTC())
            .executionProgress(
                AgentEventType.STAGNATION_UPDATED,
                "run",
                new ExecutionProgressPayload(null, 0, 3, 0, 1, "waiting_for_dependency"));
    assertEquals(
        "waiting_for_dependency",
        WebApiEventMapper.from(waiting, false, "api-secret").payload().get("reason"));
  }
}
