package dev.mikoto2000.rei.topic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventFactory;

class TopicGeneratorProjectionTest {
  private final AgentEventFactory events = new AgentEventFactory(
      Clock.fixed(Instant.parse("2026-09-02T00:00:00Z"), ZoneOffset.UTC));

  @Test
  void rebuildsTopicGeneratorStateFromLifecycleEvents() {
    TopicGeneratorProjection projection = new TopicGeneratorProjection();
    TopicScoreBreakdown score = new TopicScoreBreakdown(0.2, 0.2, 0.3, 0.2, 0, 0, 0.9);

    apply(projection,
        events.topicGenerationStarted("run", "tg-1", "agent-run"),
        events.topicCandidateGenerated("run", "tg-1", "topic-1", "FOLLOW_UP", "CONVERSATION",
            "topic", "reason", 1d, 1d, 1d, 0d, 1d),
        events.topicCandidateScored("run", "tg-1", "topic-1", score),
        events.topicSelected("run", "tg-1", "topic-1", 0.9, 1),
        events.topicSpoken("run", "tg-1", "topic-1", "m1", Instant.parse("2026-09-02T00:00:00Z"), "message"),
        events.topicGenerationCompleted("run", "tg-1", 1, 1, 0, "topic-1", true, 12));

    TopicGeneratorState state = projection.currentState();
    assertEquals(TopicGenerationStatus.COMPLETED, state.status());
    assertEquals("tg-1", state.topicGenerationId());
    assertEquals("topic-1", state.selectedCandidateId());
    assertTrue(state.spoken());
    assertEquals(Instant.parse("2026-09-02T00:00:00Z"), state.lastSpokenAt());
    assertEquals(TopicCandidateStatus.SPOKEN, state.candidates().getFirst().status());
    assertEquals(score, state.candidates().getFirst().score());
  }

  @Test
  void storesRejectedAndSkippedStateSeparately() {
    TopicGeneratorProjection projection = new TopicGeneratorProjection();

    apply(projection,
        events.topicGenerationStarted("run", "tg-1", "agent-run"),
        events.topicCandidateGenerated("run", "tg-1", "topic-1", "FOLLOW_UP", "CONVERSATION",
            "topic", "reason", 1d, 1d, 1d, 0d, 1d),
        events.topicCandidateRejected("run", "tg-1", "topic-1", TopicRejectionReason.LOW_SCORE, 0.4),
        events.topicSpeakSkipped("run", "tg-1", null, TopicSpeakSkipReason.NO_CANDIDATE, null),
        events.topicGenerationCompleted("run", "tg-1", 1, 1, 1, null, false, 12));

    TopicGeneratorState state = projection.currentState();
    assertEquals(TopicGenerationStatus.COMPLETED, state.status());
    assertEquals(TopicSpeakSkipReason.NO_CANDIDATE, state.skipReason());
    assertNull(state.selectedCandidateId());
    assertEquals(TopicCandidateStatus.REJECTED, state.candidates().getFirst().status());
    assertEquals(TopicRejectionReason.LOW_SCORE, state.candidates().getFirst().rejectionReason());
  }

  @Test
  void resetsCandidatesOnNewGenerationButKeepsLastSpokenAt() {
    TopicGeneratorProjection projection = new TopicGeneratorProjection();
    apply(projection,
        events.topicGenerationStarted("run", "tg-1", "agent-run"),
        events.topicCandidateGenerated("run", "tg-1", "topic-1", "FOLLOW_UP", "CONVERSATION",
            "topic", "reason", 1d, 1d, 1d, 0d, 1d),
        events.topicSpoken("run", "tg-1", "topic-1", "m1", Instant.parse("2026-09-02T00:00:00Z"), "message"),
        events.topicGenerationStarted("run", "tg-2", "agent-run"));

    TopicGeneratorState state = projection.currentState();
    assertEquals(TopicGenerationStatus.GENERATING, state.status());
    assertEquals("tg-2", state.topicGenerationId());
    assertTrue(state.candidates().isEmpty());
    assertEquals(Instant.parse("2026-09-02T00:00:00Z"), state.lastSpokenAt());
  }

  @Test
  void toleratesOutOfOrderCandidateEventsWithPlaceholder() {
    TopicGeneratorProjection projection = new TopicGeneratorProjection();
    TopicScoreBreakdown score = new TopicScoreBreakdown(0, 0, 0, 0, 0, 0, 0.7);

    projection.onEvent(events.topicCandidateScored("run", "tg-1", "missing", score));

    TopicGeneratorState state = projection.currentState();
    assertEquals(TopicGenerationStatus.EVALUATING, state.status());
    assertEquals("missing", state.candidates().getFirst().candidateId());
    assertEquals(TopicCandidateStatus.SCORED, state.candidates().getFirst().status());
  }

  @Test
  void ignoresUnknownAndStaleEvents() {
    TopicGeneratorProjection projection = new TopicGeneratorProjection();
    AgentEvent started = withSequence(events.topicGenerationStarted("run", "tg-1", "agent-run"), 2);
    AgentEvent stale = withSequence(events.topicGenerationStarted("run", "tg-stale", "agent-run"), 1);

    projection.onEvent(events.messageStarted("m1", "assistant"));
    projection.onEvent(started);
    projection.onEvent(stale);

    assertEquals("tg-1", projection.currentState().topicGenerationId());
    assertEquals(2, projection.currentState().lastSequence());
  }

  private void apply(TopicGeneratorProjection projection, AgentEvent... events) {
    for (AgentEvent event : events) {
      projection.onEvent(event);
    }
  }

  private AgentEvent withSequence(AgentEvent event, long sequence) {
    return new AgentEvent(event.id(), sequence, event.timestamp(), event.type(), event.version(), event.sessionId(),
        event.turnId(), event.runId(), event.correlationId(), event.parentEventId(), event.payload());
  }
}
