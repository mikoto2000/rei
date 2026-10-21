package dev.mikoto2000.rei.event;

import java.time.Instant;

/**
 * 実行状況の通知で受け渡す話題生成完了通知内容を表す。
 *
 * @param topicGenerationId 話題生成識別子
 * @param candidateCount 候補件数
 * @param scoredCount 評価済み件数
 * @param rejectedCount 拒否済み件数
 * @param selectedCandidateId 選択中候補識別子
 * @param spoken 読み上げ済み
 * @param durationMs 継続時間ミリ秒
 * @param completedAt 完了日時
 */
public record TopicGenerationCompletedPayload(
    String topicGenerationId,
    int candidateCount,
    int scoredCount,
    int rejectedCount,
    String selectedCandidateId,
    boolean spoken,
    long durationMs,
    Instant completedAt)
    implements AgentEventPayload {}
