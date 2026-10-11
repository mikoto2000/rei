package dev.mikoto2000.rei.chatgpt;

import static org.junit.jupiter.api.Assertions.*;

import dev.mikoto2000.rei.event.*;
import dev.mikoto2000.rei.web.WebApiEventDto;
import java.time.Clock;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PlanEventBoundaryTest {
  @Test
  void managementEventsIdentifyPlanUsageWithoutTaskOrFailureBody() {
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
            "private remote body",
            "chatgpt-plan",
            "selected-model",
            17);
    var payload = WebApiEventDto.from(event, false, "irrelevant-api-key").payload();
    assertEquals(
        Set.of("agentId", "status", "duration", "backendId", "modelId", "totalTokens"),
        payload.keySet());
    assertEquals("chatgpt-plan", payload.get("backendId"));
    assertEquals("selected-model", payload.get("modelId"));
    assertEquals(17, payload.get("totalTokens"));
    assertFalse(payload.toString().contains("private"));
  }
}
