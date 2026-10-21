package dev.mikoto2000.rei.chatgpt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventType;
import dev.mikoto2000.rei.web.WebApiEventDto;
import java.time.Clock;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 計画イベント境界条件の結果と状態の契約を検証するテスト。 */
class PlanEventBoundaryTest {
  /** ChatGPTとの連携で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void managementEventsIdentifyPlanUsageAndSafeFailureWithoutTaskBody() {
    var factory = new AgentEventFactory(Clock.systemUTC());
    var event =
        factory.subAgentLifecycle(
            AgentEventType.SUBAGENT_FAILED,
            "parent",
            "child",
            "reviewer",
            "private task material",
            "OUTCOME_UNKNOWN",
            120,
            "OUTCOME_UNKNOWN",
            "chatgpt-plan",
            "selected-model",
            17);
    var payload = WebApiEventDto.from(event, false, "irrelevant-api-key").payload();
    assertEquals(
        Set.of(
            "parentRunId",
            "subAgentRunId",
            "agentId",
            "status",
            "duration",
            "failureReason",
            "backendId",
            "modelId",
            "totalTokens"),
        payload.keySet());
    assertEquals("parent", payload.get("parentRunId"));
    assertEquals("child", payload.get("subAgentRunId"));
    assertEquals("reviewer", payload.get("agentId"));
    assertEquals("OUTCOME_UNKNOWN", payload.get("status"));
    assertEquals(120L, ((Number) payload.get("duration")).longValue());
    assertEquals("OUTCOME_UNKNOWN", payload.get("failureReason"));
    assertEquals("chatgpt-plan", payload.get("backendId"));
    assertEquals("selected-model", payload.get("modelId"));
    assertEquals(17, payload.get("totalTokens"));
    assertFalse(payload.toString().contains("private"));
  }
}
