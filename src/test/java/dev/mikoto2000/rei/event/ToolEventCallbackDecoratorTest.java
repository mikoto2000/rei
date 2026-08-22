package dev.mikoto2000.rei.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;

class ToolEventCallbackDecoratorTest {

  private final Clock clock = Clock.fixed(Instant.parse("2026-08-16T16:30:20Z"), ZoneId.of("Asia/Tokyo"));
  private final AgentEventFactory factory = new AgentEventFactory(clock);
  private final InMemoryAgentEventBus bus = new InMemoryAgentEventBus();
  private final List<AgentEvent> received = new ArrayList<>();

  private ToolCallback delegate(String toolName, String result) {
    ToolDefinition definition = mock(ToolDefinition.class);
    when(definition.name()).thenReturn(toolName);
    ToolCallback callback = mock(ToolCallback.class);
    when(callback.getToolDefinition()).thenReturn(definition);
    when(callback.call("input")).thenReturn(result);
    return callback;
  }

  @Test
  void callEmitsStartedThenCompleted() {
    bus.subscribe(received::add);
    ToolCallback delegate = delegate("readMultiFile", "2 files");
    ToolCallback decorated = new ToolEventCallbackDecorator(delegate, factory, bus);

    String result = decorated.call("input");

    assertEquals("2 files", result);
    assertEquals(2, received.size());
    assertEquals(AgentEventType.TOOL_STARTED, received.get(0).type());
    assertEquals(AgentEventType.TOOL_COMPLETED, received.get(1).type());
    assertEquals("readMultiFile", ((ToolStartedPayload) received.get(0).payload()).toolName());
    assertEquals("readMultiFile", ((ToolCompletedPayload) received.get(1).payload()).toolName());
    // 同一 correlationId で関連付く
    assertEquals(received.get(0).correlationId(), received.get(1).correlationId());
  }

  @Test
  void callEmitsFailedWhenDelegateThrows() {
    bus.subscribe(received::add);
    ToolDefinition definition = mock(ToolDefinition.class);
    when(definition.name()).thenReturn("readMultiFile");
    ToolCallback delegate = mock(ToolCallback.class);
    when(delegate.getToolDefinition()).thenReturn(definition);
    when(delegate.call("input")).thenThrow(new RuntimeException("boom"));
    ToolCallback decorated = new ToolEventCallbackDecorator(delegate, factory, bus);

    try {
      decorated.call("input");
    } catch (RuntimeException expected) {
      // expected
    }

    assertEquals(2, received.size());
    assertEquals(AgentEventType.TOOL_STARTED, received.get(0).type());
    assertEquals(AgentEventType.TOOL_FAILED, received.get(1).type());
    ToolFailedPayload failed = (ToolFailedPayload) received.get(1).payload();
    assertEquals("RuntimeException", failed.error().errorType());
    assertEquals("boom", failed.error().message());
    assertEquals(received.get(0).correlationId(), received.get(1).correlationId());
  }

  @Test
  void delegatesToolDefinition() {
    ToolCallback delegate = delegate("readMultiFile", "2 files");
    ToolCallback decorated = new ToolEventCallbackDecorator(delegate, factory, bus);

    assertSame(delegate.getToolDefinition(), decorated.getToolDefinition());
  }

  @Test
  void completedDoesNotContainFullResult() {
    bus.subscribe(received::add);
    ToolCallback delegate = delegate("readMultiFile", "2 files");
    ToolCallback decorated = new ToolEventCallbackDecorator(delegate, factory, bus);

    decorated.call("input");

    ToolCompletedPayload completed = (ToolCompletedPayload) received.get(1).payload();
    assertEquals("2 files", completed.resultSummary());
    assertTrue(received.get(1).payload().toString().length() < 200);
  }

  @Test
  void providerWrapsAllCallbacks() {
    bus.subscribe(received::add);
    ToolCallback a = delegate("toolA", "a");
    ToolCallback b = delegate("toolB", "b");
    ToolCallbackProvider provider = mock(ToolCallbackProvider.class);
    when(provider.getToolCallbacks()).thenReturn(new ToolCallback[] {a, b});
    ToolCallbackProvider decoratedProvider =
        new ToolEventCallbackProvider(provider, factory, bus);

    ToolCallback[] callbacks = decoratedProvider.getToolCallbacks();

    assertEquals(2, callbacks.length);
    callbacks[0].call("input");
    callbacks[1].call("input");
    assertEquals(4, received.size());
    assertEquals("toolA", ((ToolStartedPayload) received.get(0).payload()).toolName());
    assertEquals("toolB", ((ToolStartedPayload) received.get(2).payload()).toolName());
  }

  @Test
  void callWithToolContextEmitsEvents() {
    bus.subscribe(received::add);
    ToolDefinition definition = mock(ToolDefinition.class);
    when(definition.name()).thenReturn("readMultiFile");
    ToolCallback delegate = mock(ToolCallback.class);
    when(delegate.getToolDefinition()).thenReturn(definition);
    when(delegate.call(eq("input"), any(ToolContext.class))).thenReturn("ok");
    ToolCallback decorated = new ToolEventCallbackDecorator(delegate, factory, bus);

    String result = decorated.call("input", new ToolContext(java.util.Map.of()));

    assertEquals("ok", result);
    assertEquals(2, received.size());
    assertEquals(AgentEventType.TOOL_STARTED, received.get(0).type());
    assertEquals(AgentEventType.TOOL_COMPLETED, received.get(1).type());
  }
}
