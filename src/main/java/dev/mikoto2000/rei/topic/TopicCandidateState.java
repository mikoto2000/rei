package dev.mikoto2000.rei.topic;

/**
 * 話題の管理で受け渡す話題候補状態を表す。
 *
 * @param candidateId 候補識別子
 * @param status 状態
 * @param type 種別
 * @param source 取得元
 * @param topic 話題
 * @param reason 理由
 * @param score 評価値
 * @param rejectionReason rejection理由
 */
public record TopicCandidateState(
    String candidateId,
    TopicCandidateStatus status,
    TopicType type,
    TopicSource source,
    String topic,
    String reason,
    TopicScoreBreakdown score,
    TopicRejectionReason rejectionReason) {}
