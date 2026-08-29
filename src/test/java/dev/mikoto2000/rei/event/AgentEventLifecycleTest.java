package dev.mikoto2000.rei.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class AgentEventLifecycleTest {

  private final Clock clock = Clock.fixed(Instant.parse("2026-08-16T16:30:20Z"), ZoneId.of("Asia/Tokyo"));
  private final AgentEventFactory factory = new AgentEventFactory(clock);
  private final InMemoryAgentEventBus bus = new InMemoryAgentEventBus();
  private final List<AgentEvent> received = new ArrayList<>();

  private void subscribe() {
    bus.subscribe(received::add);
  }

  @Test
  void messageLifecycleEmitsStartedDeltaCompleted() {
    subscribe();

    bus.publish(factory.messageStarted("msg-1", "assistant"));
    bus.publish(factory.messageDelta("msg-1", "hel"));
    bus.publish(factory.messageDelta("msg-1", "lo"));
    bus.publish(factory.messageCompleted("msg-1", "assistant", "hello"));

    assertEquals(4, received.size());
    assertEquals(AgentEventType.MESSAGE_STARTED, received.get(0).type());
    assertEquals(AgentEventType.MESSAGE_DELTA, received.get(1).type());
    assertEquals(AgentEventType.MESSAGE_DELTA, received.get(2).type());
    assertEquals(AgentEventType.MESSAGE_COMPLETED, received.get(3).type());
    assertTrue(received.get(0).sequence() < received.get(1).sequence());
    assertTrue(received.get(1).sequence() < received.get(2).sequence());
    assertTrue(received.get(2).sequence() < received.get(3).sequence());
  }

  @Test
  void multipleDeltasReceivedInOrder() {
    subscribe();

    bus.publish(factory.messageDelta("msg-1", "a"));
    bus.publish(factory.messageDelta("msg-1", "b"));
    bus.publish(factory.messageDelta("msg-1", "c"));

    assertEquals(3, received.size());
    assertEquals("a", ((MessageDeltaPayload) received.get(0).payload()).delta());
    assertEquals("b", ((MessageDeltaPayload) received.get(1).payload()).delta());
    assertEquals("c", ((MessageDeltaPayload) received.get(2).payload()).delta());
  }

  @Test
  void thinkingLifecycleEmitsStartedDeltaCompleted() {
    subscribe();

    bus.publish(factory.thinkingStarted("thinking-1"));
    bus.publish(factory.thinkingDelta("thinking-1", "考え"));
    bus.publish(factory.thinkingDelta("thinking-1", "ます"));
    bus.publish(factory.thinkingCompleted("thinking-1", "考えます"));

    assertEquals(AgentEventType.THINKING_STARTED, received.get(0).type());
    assertEquals(AgentEventType.THINKING_DELTA, received.get(1).type());
    assertEquals(AgentEventType.THINKING_DELTA, received.get(2).type());
    assertEquals(AgentEventType.THINKING_COMPLETED, received.get(3).type());
    assertEquals("考えます", ((ThinkingCompletedPayload) received.get(3).payload()).text());
  }

  @Test
  void toolStartedAndCompletedShareCorrelationId() {
    subscribe();

    bus.publish(factory.toolStarted("call-1", "readMultiFile", "files=[a.txt]"));
    bus.publish(factory.toolCompleted("call-1", "readMultiFile", 500L, "2 files"));

    assertEquals(2, received.size());
    assertEquals("call-1", received.get(0).correlationId());
    assertEquals("call-1", received.get(1).correlationId());
    assertEquals(AgentEventType.TOOL_STARTED, received.get(0).type());
    assertEquals(AgentEventType.TOOL_COMPLETED, received.get(1).type());
  }

  @Test
  void toolFailedSharesCorrelationId() {
    subscribe();

    bus.publish(factory.toolStarted("call-2", "readMultiFile", "files=[a.txt]"));
    bus.publish(factory.toolFailed("call-2", "readMultiFile", new ErrorInformation("IOException", "boom", null)));

    assertEquals(2, received.size());
    assertEquals("call-2", received.get(0).correlationId());
    assertEquals("call-2", received.get(1).correlationId());
    assertEquals(AgentEventType.TOOL_FAILED, received.get(1).type());
  }

  @Test
  void toolCompletedDoesNotContainFullResult() {
    subscribe();

    bus.publish(factory.toolStarted("call-1", "readMultiFile", "files=[a.txt]"));
    bus.publish(factory.toolCompleted("call-1", "readMultiFile", 500L, "2 files", 2, 1234L, null, null));

    ToolCompletedPayload completed = (ToolCompletedPayload) received.get(1).payload();
    assertEquals("2 files", completed.resultSummary());
    assertEquals(2, completed.files());
    assertEquals(1234L, completed.bytes());
    // 結果全文が payload に含まれないことを確認する
    assertTrue(received.get(1).payload().toString().length() < 200);
  }
}
