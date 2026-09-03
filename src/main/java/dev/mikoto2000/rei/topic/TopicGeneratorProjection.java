package dev.mikoto2000.rei.topic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventListener;
import dev.mikoto2000.rei.event.TopicCandidateGeneratedPayload;
import dev.mikoto2000.rei.event.TopicCandidateRejectedPayload;
import dev.mikoto2000.rei.event.TopicCandidateScoredPayload;
import dev.mikoto2000.rei.event.TopicGenerationCompletedPayload;
import dev.mikoto2000.rei.event.TopicGenerationFailedPayload;
import dev.mikoto2000.rei.event.TopicGenerationStartedPayload;
import dev.mikoto2000.rei.event.TopicSelectedPayload;
import dev.mikoto2000.rei.event.TopicSpeakSkippedPayload;
import dev.mikoto2000.rei.event.TopicSpokenPayload;

public final class TopicGeneratorProjection implements AgentEventListener {
  private static final Logger log = LoggerFactory.getLogger(TopicGeneratorProjection.class);

  private TopicGenerationStatus status = TopicGenerationStatus.IDLE;
  private String topicGenerationId;
  private final Map<String, TopicCandidateState> candidates = new LinkedHashMap<>();
  private String selectedCandidateId;
  private TopicSpeakSkipReason skipReason;
  private java.time.Instant startedAt;
  private java.time.Instant completedAt;
  private java.time.Instant lastSpokenAt;
  private boolean spoken;
  private long lastSequence;

  @Override
  public synchronized void onEvent(AgentEvent event) {
    if (event == null) return;
    if (event.sequence() > 0 && event.sequence() <= lastSequence) {
      log.debug("Ignoring stale Topic Generator event: sequence={}, lastSequence={}", event.sequence(), lastSequence);
      return;
    }
    try {
      switch (event.type()) {
        case TOPIC_GENERATION_STARTED -> applyStarted((TopicGenerationStartedPayload) event.payload());
        case TOPIC_CANDIDATE_GENERATED -> applyGenerated((TopicCandidateGeneratedPayload) event.payload());
        case TOPIC_CANDIDATE_SCORED -> applyScored((TopicCandidateScoredPayload) event.payload());
        case TOPIC_CANDIDATE_REJECTED -> applyRejected((TopicCandidateRejectedPayload) event.payload());
        case TOPIC_SELECTED -> applySelected((TopicSelectedPayload) event.payload());
        case TOPIC_SPEAK_SKIPPED -> applySkipped((TopicSpeakSkippedPayload) event.payload());
        case TOPIC_SPOKEN -> applySpoken((TopicSpokenPayload) event.payload());
        case TOPIC_GENERATION_COMPLETED -> applyCompleted((TopicGenerationCompletedPayload) event.payload());
        case TOPIC_GENERATION_FAILED -> applyFailed((TopicGenerationFailedPayload) event.payload());
        default -> { }
      }
      lastSequence = Math.max(lastSequence, event.sequence());
    } catch (RuntimeException exception) {
      log.warn("Topic Generator Projection ignored malformed event: type={}, id={}", event.type(), event.id(), exception);
    }
  }

  public synchronized TopicGeneratorState currentState() {
    return new TopicGeneratorState(status, topicGenerationId, new ArrayList<>(candidates.values()),
        selectedCandidateId, skipReason, startedAt, completedAt, lastSpokenAt, spoken, lastSequence);
  }

  private void applyStarted(TopicGenerationStartedPayload payload) {
    java.time.Instant previousLastSpokenAt = lastSpokenAt;
    status = TopicGenerationStatus.GENERATING;
    topicGenerationId = payload.topicGenerationId();
    candidates.clear();
    selectedCandidateId = null;
    skipReason = null;
    startedAt = payload.startedAt();
    completedAt = null;
    lastSpokenAt = previousLastSpokenAt;
    spoken = false;
  }

  private void applyGenerated(TopicCandidateGeneratedPayload payload) {
    status = TopicGenerationStatus.GENERATING;
    TopicType type = parseEnum(TopicType.class, payload.topicType());
    TopicSource source = parseEnum(TopicSource.class, payload.source());
    candidates.put(payload.candidateId(), new TopicCandidateState(payload.candidateId(), TopicCandidateStatus.GENERATED,
        type, source, payload.topicSummary(), payload.reasonSummary(), null, null));
  }

  private void applyScored(TopicCandidateScoredPayload payload) {
    status = TopicGenerationStatus.EVALUATING;
    TopicCandidateState current = candidate(payload.candidateId());
    candidates.put(payload.candidateId(), new TopicCandidateState(current.candidateId(), TopicCandidateStatus.SCORED,
        current.type(), current.source(), current.topic(), current.reason(), payload.score(), current.rejectionReason()));
  }

  private void applyRejected(TopicCandidateRejectedPayload payload) {
    TopicCandidateState current = candidate(payload.candidateId());
    TopicScoreBreakdown score = current.score();
    if (score == null && payload.score() != null) {
      score = new TopicScoreBreakdown(0, 0, 0, 0, 0, 0, payload.score());
    }
    candidates.put(payload.candidateId(), new TopicCandidateState(current.candidateId(), TopicCandidateStatus.REJECTED,
        current.type(), current.source(), current.topic(), current.reason(), score, payload.reason()));
  }

  private void applySelected(TopicSelectedPayload payload) {
    status = TopicGenerationStatus.READY;
    selectedCandidateId = payload.candidateId();
    TopicCandidateState current = candidate(payload.candidateId());
    TopicScoreBreakdown score = current.score();
    if (score == null && payload.score() != null) {
      score = new TopicScoreBreakdown(0, 0, 0, 0, 0, 0, payload.score());
    }
    candidates.put(payload.candidateId(), new TopicCandidateState(current.candidateId(), TopicCandidateStatus.SELECTED,
        current.type(), current.source(), current.topic(), current.reason(), score, current.rejectionReason()));
  }

  private void applySkipped(TopicSpeakSkippedPayload payload) {
    status = TopicGenerationStatus.SKIPPED;
    skipReason = payload.reason();
  }

  private void applySpoken(TopicSpokenPayload payload) {
    status = TopicGenerationStatus.SPOKEN;
    spoken = true;
    lastSpokenAt = payload.spokenAt();
    TopicCandidateState current = candidate(payload.candidateId());
    candidates.put(payload.candidateId(), new TopicCandidateState(current.candidateId(), TopicCandidateStatus.SPOKEN,
        current.type(), current.source(), current.topic(), current.reason(), current.score(), current.rejectionReason()));
  }

  private void applyCompleted(TopicGenerationCompletedPayload payload) {
    status = TopicGenerationStatus.COMPLETED;
    topicGenerationId = payload.topicGenerationId();
    completedAt = payload.completedAt();
    selectedCandidateId = payload.selectedCandidateId();
    spoken = payload.spoken();
  }

  private void applyFailed(TopicGenerationFailedPayload payload) {
    status = TopicGenerationStatus.FAILED;
    topicGenerationId = payload.topicGenerationId();
    completedAt = payload.failedAt();
  }

  private TopicCandidateState candidate(String candidateId) {
    TopicCandidateState current = candidates.get(candidateId);
    if (current != null) return current;
    return new TopicCandidateState(candidateId, TopicCandidateStatus.GENERATED, null, null, null, null, null, null);
  }

  private static <T extends Enum<T>> T parseEnum(Class<T> type, String value) {
    if (value == null || value.isBlank()) return null;
    try {
      return Enum.valueOf(type, value);
    } catch (IllegalArgumentException ignored) {
      return null;
    }
  }
}
