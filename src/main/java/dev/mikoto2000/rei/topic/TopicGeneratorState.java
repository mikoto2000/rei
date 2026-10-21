package dev.mikoto2000.rei.topic;

import java.time.Instant;
import java.util.List;

/**
 * 話題の管理で受け渡す話題生成状態を表す。
 *
 * @param status 状態
 * @param topicGenerationId 話題生成識別子
 * @param candidates 候補一覧
 * @param selectedCandidateId 選択中候補識別子
 * @param skipReason 対象外理由
 * @param startedAt 開始日時
 * @param completedAt 完了日時
 * @param lastSpokenAt 直近読み上げ済み日時
 * @param spoken 読み上げ済み
 * @param lastSequence 直近発生順序
 */
public record TopicGeneratorState(
    TopicGenerationStatus status,
    String topicGenerationId,
    List<TopicCandidateState> candidates,
    String selectedCandidateId,
    TopicSpeakSkipReason skipReason,
    Instant startedAt,
    Instant completedAt,
    Instant lastSpokenAt,
    boolean spoken,
    long lastSequence) {

  /** 話題の管理で用いる操作がない状態を返す。 */
  public static TopicGeneratorState idle() {
    return new TopicGeneratorState(
        TopicGenerationStatus.IDLE, null, List.of(), null, null, null, null, null, false, 0L);
  }
}
