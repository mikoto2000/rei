package dev.mikoto2000.rei.event;

import dev.mikoto2000.rei.topic.TopicRejectionReason;

/**
 * 実行状況の通知で受け渡す話題候補拒否済み通知内容を表す。
 *
 * @param topicGenerationId 話題生成識別子
 * @param candidateId 候補識別子
 * @param reason 理由
 * @param score 評価値
 */
public record TopicCandidateRejectedPayload(
    String topicGenerationId, String candidateId, TopicRejectionReason reason, Double score)
    implements AgentEventPayload {}
