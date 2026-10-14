package dev.mikoto2000.rei.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

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

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"readBinaryFile", "readPdfFile", "copyFile"})
  void missingFileReturnsFailureAndPreservesFailedEvent(String toolName) {
    bus.subscribe(received::add);
    var callback = delegate(toolName, "unused");
    when(callback.call("input")).thenThrow(new IllegalStateException(
        new java.nio.file.NoSuchFileException("PRIVATE_PATH")));
    String result = new ToolEventCallbackDecorator(callback, factory, bus).call("input");

    assertTrue(dev.mikoto2000.rei.core.stagnation.ProgressEvaluator.failedResult(result));
    assertTrue(result.contains("source_missing"));
    assertTrue(result.contains("findFile"));
    assertTrue(!result.contains("PRIVATE_PATH"));
    assertEquals(List.of(AgentEventType.TOOL_STARTED, AgentEventType.TOOL_FAILED),
        received.stream().map(AgentEvent::type).toList());
    assertEquals(received.get(0).correlationId(), received.get(1).correlationId());
  }

  @Test
  void missingFileWithContextReturnsFailure() {
    bus.subscribe(received::add);
    var callback = delegate("readBinaryFile", "unused");
    when(callback.call(eq("input"), any(ToolContext.class))).thenThrow(
        new java.io.UncheckedIOException(new java.io.FileNotFoundException("PRIVATE_PATH")));
    String result = new ToolEventCallbackDecorator(callback, factory, bus).call("input",
        new ToolContext(java.util.Map.of("toolCallId", "missing-file-call")));

    assertTrue(dev.mikoto2000.rei.core.stagnation.ProgressEvaluator.failedResult(result));
    assertTrue(result.contains("file_unavailable"));
    assertEquals("missing-file-call", received.get(1).correlationId());
    assertEquals(AgentEventType.TOOL_FAILED, received.get(1).type());
  }

  @Test
  void internalMissingResourceStillPropagates() {
    var callback = delegate("updateTaskState", "unused");
    var failure = new IllegalStateException(new java.nio.file.NoSuchFileException("storage"));
    when(callback.call("input")).thenThrow(failure);
    assertSame(failure, assertThrows(IllegalStateException.class,
        () -> new ToolEventCallbackDecorator(callback, factory, bus).call("input")));
  }

  @Test
  void unrelatedFailuresStillPropagate() {
    var callback = delegate("readBinaryFile", "unused");
    for (var failure : List.of(
        new java.io.UncheckedIOException(new java.nio.file.AccessDeniedException("denied")),
        new IllegalStateException(new java.io.IOException("disk failure")),
        new SecurityException("denied"),
        new IllegalStateException(new java.util.concurrent.CancellationException()))) {
      org.mockito.Mockito.doThrow(failure).when(callback).call("input");
      assertSame(failure, assertThrows(RuntimeException.class,
          () -> new ToolEventCallbackDecorator(callback, factory, bus).call("input")));
    }
  }

  @Test
  void cancellationDuringMissingFileReadStillPropagates() {
    var callback = delegate("readBinaryFile", "unused");
    var failure = new java.io.UncheckedIOException(new java.nio.file.NoSuchFileException("missing"));
    when(callback.call("input")).thenAnswer(invocation -> {
      Thread.currentThread().interrupt();
      throw failure;
    });
    try {
      assertSame(failure, assertThrows(RuntimeException.class,
          () -> new ToolEventCallbackDecorator(callback, factory, bus).call("input")));
    } finally {
      Thread.interrupted();
    }
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

  @Test
  void suppliedToolCallIdIsUsedForStartedAndCompleted() {
    bus.subscribe(received::add);
    ToolCallback delegate = delegate("readMultiFile", "ok");
    ToolCallback decorated = new ToolEventCallbackDecorator(delegate, factory, bus);

    decorated.call("input", new ToolContext(java.util.Map.of("toolCallId", "call-from-model")));

    assertEquals("call-from-model", received.get(0).correlationId());
    assertEquals("call-from-model", received.get(1).correlationId());
  }

  @Test
  void methodToolProviderCallbacksAreObserved() {
    bus.subscribe(received::add);
    MethodToolCallbackProvider provider = MethodToolCallbackProvider.builder()
        .toolObjects(new SampleMethodTools())
        .build();
    ToolCallback decorated = new ToolEventCallbackProvider(provider, factory, bus).getToolCallbacks()[0];

    assertEquals("\"hello Rei\"", decorated.call("{\"name\":\"Rei\"}"));
    assertEquals(AgentEventType.TOOL_STARTED, received.get(0).type());
    assertEquals(AgentEventType.TOOL_COMPLETED, received.get(1).type());
    assertEquals("hello", ((ToolStartedPayload) received.get(0).payload()).toolName());
  }

  @Test
  void argumentsSummaryRedactsObviousCredentials() {
    bus.subscribe(received::add);
    ToolCallback delegate = delegate("remoteCall", "ok");

    new ToolEventCallbackDecorator(delegate, factory, bus)
        .call("{\"apiKey\":\"super-secret-value\",\"query\":\"hello\"}");

    String summary = ((ToolStartedPayload) received.get(0).payload()).argumentsSummary();
    assertTrue(summary.contains("[REDACTED]"));
    assertTrue(!summary.contains("super-secret-value"));
  }

  public static class SampleMethodTools {
    @Tool(name = "hello", description = "returns a greeting")
    public String hello(String name) {
      return "hello " + name;
    }
  }
}
