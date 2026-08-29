package dev.mikoto2000.rei.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

class AgentEventFactoryTest {

  private final Clock clock = Clock.fixed(Instant.parse("2026-08-16T16:30:20Z"), ZoneId.of("Asia/Tokyo"));
  private final AgentEventFactory factory = new AgentEventFactory(clock);

  @Test
  void createsRunStartedEvent() {
    AgentEvent event = factory.runStarted("run-1", "user-request", null);

    assertEquals(AgentEventType.AGENT_RUN_STARTED, event.type());
    assertEquals(1, event.version());
    assertEquals("run-1", event.runId());
    assertNotNull(event.id());
    assertEquals(Instant.parse("2026-08-16T16:30:20Z"), event.timestamp());
    assertTrue(event.payload() instanceof AgentRunStartedPayload);
    AgentRunStartedPayload payload = (AgentRunStartedPayload) event.payload();
    assertEquals("run-1", payload.runId());
    assertEquals("user-request", payload.reason());
  }

  @Test
  void createsRunCompletedEvent() {
    AgentEvent event = factory.runCompleted("run-1", 1234L, 567L, 12.34d);

    assertEquals(AgentEventType.AGENT_RUN_COMPLETED, event.type());
    AgentRunCompletedPayload payload = (AgentRunCompletedPayload) event.payload();
    assertEquals("run-1", payload.runId());
    assertEquals(1234L, payload.duration());
    assertEquals(567L, payload.completionTokens());
    assertEquals(12.34d, payload.tokensPerSecond());
  }

  @Test
  void createsMessageDeltaEvent() {
    AgentEvent event = factory.messageDelta("msg-1", "hello");

    assertEquals(AgentEventType.MESSAGE_DELTA, event.type());
    MessageDeltaPayload payload = (MessageDeltaPayload) event.payload();
    assertEquals("msg-1", payload.messageId());
    assertEquals("hello", payload.delta());
  }

  @Test
  void createsThinkingDeltaEvent() {
    AgentEvent event = factory.thinkingDelta("thinking-1", "hello");

    assertEquals(AgentEventType.THINKING_DELTA, event.type());
    ThinkingDeltaPayload payload = (ThinkingDeltaPayload) event.payload();
    assertEquals("thinking-1", payload.thinkingId());
    assertEquals("hello", payload.delta());
  }

  @Test
  void createsToolStartedEvent() {
    AgentEvent event = factory.toolStarted("call-1", "readMultiFile", "files=[a.txt]");

    assertEquals(AgentEventType.TOOL_STARTED, event.type());
    ToolStartedPayload payload = (ToolStartedPayload) event.payload();
    assertEquals("call-1", payload.toolCallId());
    assertEquals("readMultiFile", payload.toolName());
    assertEquals("files=[a.txt]", payload.argumentsSummary());
  }

  @Test
  void createsToolCompletedEvent() {
    AgentEvent event = factory.toolCompleted("call-1", "readMultiFile", 500L, "2 files");

    assertEquals(AgentEventType.TOOL_COMPLETED, event.type());
    ToolCompletedPayload payload = (ToolCompletedPayload) event.payload();
    assertEquals("call-1", payload.toolCallId());
    assertEquals("readMultiFile", payload.toolName());
    assertEquals(500L, payload.duration());
    assertEquals("2 files", payload.resultSummary());
  }

  @Test
  void createsToolFailedEvent() {
    AgentEvent event = factory.toolFailed("call-1", "readMultiFile", new ErrorInformation("IOException", "boom", null));

    assertEquals(AgentEventType.TOOL_FAILED, event.type());
    ToolFailedPayload payload = (ToolFailedPayload) event.payload();
    assertEquals("call-1", payload.toolCallId());
    assertEquals("readMultiFile", payload.toolName());
    assertEquals("IOException", payload.error().errorType());
  }

  @Test
  void createsSkillRoutingLifecycleEventsWithTypedMetrics() {
    AgentEvent started = factory.skillRoutingStarted("run-1", "routing-1", 27, 1);
    AgentEvent completed = factory.skillRoutingCompleted("run-1", "routing-1", 1_834, 27, "rspress", 1,
        1_560L, 21L, null, java.util.List.of("explicit"), java.util.List.of("implicit"),
        java.util.List.of("warning"));

    assertEquals(AgentEventType.SKILL_ROUTING_STARTED, started.type());
    assertEquals(AgentEventType.SKILL_ROUTING_COMPLETED, completed.type());
    assertEquals("run-1", started.runId());
    assertEquals("routing-1", started.correlationId());
    assertEquals("routing-1", completed.correlationId());
    SkillRoutingStartedPayload startedPayload = (SkillRoutingStartedPayload) started.payload();
    assertEquals(27, startedPayload.candidateCount());
    assertEquals(1, startedPayload.routingInvocation());
    SkillRoutingCompletedPayload payload = (SkillRoutingCompletedPayload) completed.payload();
    assertEquals(1_834, payload.durationMs());
    assertEquals("rspress", payload.selectedSkill());
    assertEquals(1_560L, payload.selectorDurationMs());
    assertEquals(java.util.List.of("explicit"), payload.explicitSkillNames());
    assertEquals(java.util.List.of("implicit"), payload.implicitSkillNames());
    assertEquals(java.util.List.of("warning"), payload.warnings());
  }

  @Test
  void correlationIdIsOptional() {
    AgentEvent event = factory.runStarted("run-1", "user-request", null);
    assertNull(event.correlationId());
  }

  @Test
  void sessionTurnRunIdsArePropagated() {
    AgentEvent event = factory.runStarted("run-1", "user-request", null)
        .withContext("session-1", "turn-1");

    assertEquals("session-1", event.sessionId());
    assertEquals("turn-1", event.turnId());
    assertEquals("run-1", event.runId());
  }
}
