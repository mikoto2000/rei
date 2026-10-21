package dev.mikoto2000.rei.topic;

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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 話題の管理に必要な話題生成表示用の状態を扱う。 */
public final class TopicGeneratorProjection implements AgentEventListener {
  /** 処理状況と問題の調査に使うログの出力先。 */
  private static final Logger log = LoggerFactory.getLogger(TopicGeneratorProjection.class);

  /** 状態。 */
  private TopicGenerationStatus status = TopicGenerationStatus.IDLE;

  /** 話題生成識別子。 */
  private String topicGenerationId;

  /** 候補一覧。 */
  private final Map<String, TopicCandidateState> candidates = new LinkedHashMap<>();

  /** 選択中候補識別子。 */
  private String selectedCandidateId;

  /** 対象外理由。 */
  private TopicSpeakSkipReason skipReason;

  /** 開始日時。 */
  private java.time.Instant startedAt;

  /** 完了日時。 */
  private java.time.Instant completedAt;

  /** 直近読み上げ済み日時。 */
  private java.time.Instant lastSpokenAt;

  /** 読み上げ済み。 */
  private boolean spoken;

  /** 直近発生順序。 */
  private long lastSequence;

  /** 通知に応じて状態や表示を更新するため、イベントを受け付ける。 */
  @Override
  public synchronized void onEvent(AgentEvent event) {
    if (event == null) return;
    if (event.sequence() > 0 && event.sequence() <= lastSequence) {
      log.debug(
          "Ignoring stale Topic Generator event: sequence={}, lastSequence={}",
          event.sequence(),
          lastSequence);
      return;
    }
    try {
      switch (event.type()) {
        case TOPIC_GENERATION_STARTED ->
            applyStarted((TopicGenerationStartedPayload) event.payload());
        case TOPIC_CANDIDATE_GENERATED ->
            applyGenerated((TopicCandidateGeneratedPayload) event.payload());
        case TOPIC_CANDIDATE_SCORED -> applyScored((TopicCandidateScoredPayload) event.payload());
        case TOPIC_CANDIDATE_REJECTED ->
            applyRejected((TopicCandidateRejectedPayload) event.payload());
        case TOPIC_SELECTED -> applySelected((TopicSelectedPayload) event.payload());
        case TOPIC_SPEAK_SKIPPED -> applySkipped((TopicSpeakSkippedPayload) event.payload());
        case TOPIC_SPOKEN -> applySpoken((TopicSpokenPayload) event.payload());
        case TOPIC_GENERATION_COMPLETED ->
            applyCompleted((TopicGenerationCompletedPayload) event.payload());
        case TOPIC_GENERATION_FAILED -> applyFailed((TopicGenerationFailedPayload) event.payload());
        default -> {}
      }
      lastSequence = Math.max(lastSequence, event.sequence());
    } catch (RuntimeException exception) {
      log.warn(
          "Topic Generator Projection ignored malformed event: type={}, id={}",
          event.type(),
          event.id(),
          exception);
    }
  }

  /** 話題の管理で用いる現在状態を返す。 */
  public synchronized TopicGeneratorState currentState() {
    return new TopicGeneratorState(
        status,
        topicGenerationId,
        new ArrayList<>(candidates.values()),
        selectedCandidateId,
        skipReason,
        startedAt,
        completedAt,
        lastSpokenAt,
        spoken,
        lastSequence);
  }

  /** 指定された変更を有効にするため、開始を反映する。 */
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

  /** 指定された変更を有効にするため、生成済みを反映する。 */
  private void applyGenerated(TopicCandidateGeneratedPayload payload) {
    status = TopicGenerationStatus.GENERATING;
    TopicType type = parseEnum(TopicType.class, payload.topicType());
    TopicSource source = parseEnum(TopicSource.class, payload.source());
    candidates.put(
        payload.candidateId(),
        new TopicCandidateState(
            payload.candidateId(),
            TopicCandidateStatus.GENERATED,
            type,
            source,
            payload.topicSummary(),
            payload.reasonSummary(),
            null,
            null));
  }

  /** 指定された変更を有効にするため、評価済みを反映する。 */
  private void applyScored(TopicCandidateScoredPayload payload) {
    status = TopicGenerationStatus.EVALUATING;
    TopicCandidateState current = candidate(payload.candidateId());
    candidates.put(
        payload.candidateId(),
        new TopicCandidateState(
            current.candidateId(),
            TopicCandidateStatus.SCORED,
            current.type(),
            current.source(),
            current.topic(),
            current.reason(),
            payload.score(),
            current.rejectionReason()));
  }

  /** 指定された変更を有効にするため、拒否済みを反映する。 */
  private void applyRejected(TopicCandidateRejectedPayload payload) {
    TopicCandidateState current = candidate(payload.candidateId());
    TopicScoreBreakdown score = current.score();
    if (score == null && payload.score() != null) {
      score = new TopicScoreBreakdown(0, 0, 0, 0, 0, 0, payload.score());
    }
    candidates.put(
        payload.candidateId(),
        new TopicCandidateState(
            current.candidateId(),
            TopicCandidateStatus.REJECTED,
            current.type(),
            current.source(),
            current.topic(),
            current.reason(),
            score,
            payload.reason()));
  }

  /** 指定された変更を有効にするため、選択中を反映する。 */
  private void applySelected(TopicSelectedPayload payload) {
    status = TopicGenerationStatus.READY;
    selectedCandidateId = payload.candidateId();
    TopicCandidateState current = candidate(payload.candidateId());
    TopicScoreBreakdown score = current.score();
    if (score == null && payload.score() != null) {
      score = new TopicScoreBreakdown(0, 0, 0, 0, 0, 0, payload.score());
    }
    candidates.put(
        payload.candidateId(),
        new TopicCandidateState(
            current.candidateId(),
            TopicCandidateStatus.SELECTED,
            current.type(),
            current.source(),
            current.topic(),
            current.reason(),
            score,
            current.rejectionReason()));
  }

  /** 指定された変更を有効にするため、対象外を反映する。 */
  private void applySkipped(TopicSpeakSkippedPayload payload) {
    status = TopicGenerationStatus.SKIPPED;
    skipReason = payload.reason();
  }

  /** 指定された変更を有効にするため、読み上げ済みを反映する。 */
  private void applySpoken(TopicSpokenPayload payload) {
    status = TopicGenerationStatus.SPOKEN;
    spoken = true;
    lastSpokenAt = payload.spokenAt();
    TopicCandidateState current = candidate(payload.candidateId());
    candidates.put(
        payload.candidateId(),
        new TopicCandidateState(
            current.candidateId(),
            TopicCandidateStatus.SPOKEN,
            current.type(),
            current.source(),
            current.topic(),
            current.reason(),
            current.score(),
            current.rejectionReason()));
  }

  /** 指定された変更を有効にするため、完了を反映する。 */
  private void applyCompleted(TopicGenerationCompletedPayload payload) {
    status = TopicGenerationStatus.COMPLETED;
    topicGenerationId = payload.topicGenerationId();
    completedAt = payload.completedAt();
    selectedCandidateId = payload.selectedCandidateId();
    spoken = payload.spoken();
  }

  /** 指定された変更を有効にするため、失敗を反映する。 */
  private void applyFailed(TopicGenerationFailedPayload payload) {
    status = TopicGenerationStatus.FAILED;
    topicGenerationId = payload.topicGenerationId();
    completedAt = payload.failedAt();
  }

  /** 話題の管理で用いる候補を返す。 */
  private TopicCandidateState candidate(String candidateId) {
    TopicCandidateState current = candidates.get(candidateId);
    if (current != null) return current;
    return new TopicCandidateState(
        candidateId, TopicCandidateStatus.GENERATED, null, null, null, null, null, null);
  }

  /** 入力を機能内で扱えるよう、Enumを解釈する。 */
  private static <T extends Enum<T>> T parseEnum(Class<T> type, String value) {
    if (value == null || value.isBlank()) return null;
    try {
      return Enum.valueOf(type, value);
    } catch (IllegalArgumentException ignored) {
      return null;
    }
  }
}
