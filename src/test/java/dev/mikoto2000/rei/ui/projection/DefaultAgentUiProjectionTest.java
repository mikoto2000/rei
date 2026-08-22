package dev.mikoto2000.rei.ui.projection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.ErrorInformation;
import dev.mikoto2000.rei.event.InMemoryAgentEventBus;

class DefaultAgentUiProjectionTest {

  private final AgentEventFactory events = new AgentEventFactory(
      Clock.fixed(Instant.parse("2026-08-23T00:00:00Z"), ZoneOffset.UTC));

  @Test
  void initialStateIsIdleAndEmpty() {
    AgentUiState state = new DefaultAgentUiProjection().currentState();

    assertEquals(AgentRunStatus.IDLE, state.run().status());
    assertEquals(0, state.messages().size());
    assertEquals(0, state.tools().size());
    assertEquals(0L, state.lastSequence());
  }

  @Test
  void projectsRunLifecycle() {
    DefaultAgentUiProjection projection = new DefaultAgentUiProjection();
    projection.apply(sequence(events.runStarted("run-1", "user-request", null), 1));
    assertEquals(AgentRunStatus.RUNNING, projection.currentState().run().status());
    assertEquals("run-1", projection.currentState().run().runId());

    projection.apply(sequence(events.runCompleted("run-1", 42), 2));
    assertEquals(AgentRunStatus.COMPLETED, projection.currentState().run().status());
    assertEquals(42L, projection.currentState().run().durationMillis());

    projection.apply(sequence(events.runStarted("run-2", "user-request", null), 3));
    projection.apply(sequence(events.runFailed("run-2", new ErrorInformation("IOException", "boom", "E1")), 4));
    assertEquals(AgentRunStatus.FAILED, projection.currentState().run().status());
    assertEquals("IOException", projection.currentState().run().error().type());
  }

  @Test
  void accumulatesStreamingMessageAndUsesCompletedText() {
    DefaultAgentUiProjection projection = new DefaultAgentUiProjection();
    projection.apply(sequence(events.messageStarted("msg-1", "assistant"), 1));
    projection.apply(sequence(events.messageDelta("msg-1", "Hello"), 2));
    projection.apply(sequence(events.messageDelta("msg-1", " world"), 3));
    projection.apply(sequence(events.messageCompleted("msg-1", "assistant", "Hello world"), 4));

    AgentUiState state = projection.currentState();
    assertEquals(1, state.messages().size());
    assertEquals("Hello world", state.messages().getFirst().text());
    assertEquals(MessageStatus.COMPLETED, state.messages().getFirst().status());
  }

  @Test
  void projectsSuccessfulAndFailedToolsInStartOrder() {
    DefaultAgentUiProjection projection = new DefaultAgentUiProjection();
    projection.apply(sequence(events.toolStarted("call-1", "grep", "q=x"), 1));
    projection.apply(sequence(events.toolCompleted("call-1", "grep", 10, "2 matches"), 2));
    projection.apply(sequence(events.toolStarted("call-2", "read", "a.txt"), 3));
    projection.apply(sequence(events.toolFailed("call-2", "read", new ErrorInformation("IOException", "no file", null)), 4));
    projection.apply(sequence(events.toolStarted("call-3", "edit", "a.txt"), 5));

    AgentUiState state = projection.currentState();
    assertEquals(3, state.tools().size());
    assertEquals("grep", state.tools().get(0).toolName());
    assertEquals(ToolExecutionStatus.COMPLETED, state.tools().get(0).status());
    assertEquals(10L, state.tools().get(0).durationMillis());
    assertEquals(ToolExecutionStatus.FAILED, state.tools().get(1).status());
    assertEquals("IOException", state.tools().get(1).error().type());
    assertEquals(ToolExecutionStatus.RUNNING, state.tools().get(2).status());
  }

  @Test
  void sameToolNameWithDifferentCorrelationIdsCreatesTwoExecutions() {
    DefaultAgentUiProjection projection = new DefaultAgentUiProjection();
    projection.apply(sequence(events.toolStarted("call-1", "readMultiFile", "a"), 1));
    projection.apply(sequence(events.toolStarted("call-2", "readMultiFile", "b"), 2));

    assertEquals(2, projection.currentState().tools().size());
  }

  @Test
  void incompleteEventSequencesCreatePlaceholdersWithoutThrowing() {
    DefaultAgentUiProjection projection = new DefaultAgentUiProjection();
    projection.apply(sequence(events.messageDelta("missing-message", "partial"), 1));
    projection.apply(sequence(events.messageCompleted("completed-only", "assistant", "done"), 2));
    projection.apply(sequence(events.toolCompleted("missing-tool", "read", 5, "ok"), 3));
    projection.apply(sequence(events.toolFailed("failed-tool", "write", new ErrorInformation("IO", "bad", null)), 4));

    AgentUiState state = projection.currentState();
    assertEquals(2, state.messages().size());
    assertEquals("partial", state.messages().get(0).text());
    assertEquals(MessageStatus.COMPLETED, state.messages().get(1).status());
    assertEquals(2, state.tools().size());
    assertEquals(ToolExecutionStatus.COMPLETED, state.tools().get(0).status());
    assertEquals(ToolExecutionStatus.FAILED, state.tools().get(1).status());
  }

  @Test
  void ignoresStaleSequenceAndExposesLastSequence() {
    DefaultAgentUiProjection projection = new DefaultAgentUiProjection();
    projection.apply(sequence(events.messageStarted("new", "assistant"), 10));
    projection.apply(sequence(events.messageStarted("stale", "assistant"), 9));

    assertEquals(10L, projection.currentState().lastSequence());
    assertEquals(1, projection.currentState().messages().size());
  }

  @Test
  void stateCollectionsAreImmutableSnapshots() {
    DefaultAgentUiProjection projection = new DefaultAgentUiProjection();
    projection.apply(sequence(events.messageStarted("msg-1", "assistant"), 1));
    AgentUiState snapshot = projection.currentState();

    assertThrows(UnsupportedOperationException.class, snapshot.messages()::clear);
    projection.apply(sequence(events.messageStarted("msg-2", "assistant"), 2));
    assertEquals(1, snapshot.messages().size());
    assertEquals(2, projection.currentState().messages().size());
  }

  @Test
  void subscribesDirectlyToEventBus() {
    InMemoryAgentEventBus bus = new InMemoryAgentEventBus();
    DefaultAgentUiProjection projection = new DefaultAgentUiProjection();
    bus.subscribe(projection);

    bus.publish(events.runStarted("run-1", "user-request", null));
    bus.publish(events.messageStarted("msg-1", "assistant"));

    assertEquals(AgentRunStatus.RUNNING, projection.currentState().run().status());
    assertEquals(1, projection.currentState().messages().size());
    assertEquals(bus.lastSequence(), projection.currentState().lastSequence());
  }

  @Test
  void newRunDoesNotMixPreviousMessagesAndTools() {
    DefaultAgentUiProjection projection = new DefaultAgentUiProjection();
    projection.apply(sequence(events.runStarted("run-1", "user-request", null), 1));
    projection.apply(sequence(events.messageStarted("msg-1", "assistant"), 2));
    projection.apply(sequence(events.toolStarted("call-1", "read", "a"), 3));
    projection.apply(sequence(events.runStarted("run-2", "user-request", null), 4));

    assertEquals("run-2", projection.currentState().run().runId());
    assertEquals(0, projection.currentState().messages().size());
    assertEquals(0, projection.currentState().tools().size());
  }

  private AgentEvent sequence(AgentEvent event, long sequence) {
    return new AgentEvent(event.id(), sequence, event.timestamp(), event.type(), event.version(), event.sessionId(),
        event.turnId(), event.runId(), event.correlationId(), event.parentEventId(), event.payload());
  }
}
