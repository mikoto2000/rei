package dev.mikoto2000.rei.event;

import dev.mikoto2000.rei.topic.TopicScoreBreakdown;

/**
 * 実行状況の通知で受け渡す話題候補評価済み通知内容を表す。
 *
 * @param topicGenerationId 話題生成識別子
 * @param candidateId 候補識別子
 * @param score 評価値
 */
public record TopicCandidateScoredPayload(
    String topicGenerationId, String candidateId, TopicScoreBreakdown score)
    implements AgentEventPayload {}
