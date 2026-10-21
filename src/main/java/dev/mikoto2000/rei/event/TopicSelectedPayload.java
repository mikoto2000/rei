package dev.mikoto2000.rei.event;

/**
 * 実行状況の通知で受け渡す話題選択中通知内容を表す。
 *
 * @param topicGenerationId 話題生成識別子
 * @param candidateId 候補識別子
 * @param score 評価値
 * @param rank 順位
 */
public record TopicSelectedPayload(
    String topicGenerationId, String candidateId, Double score, Integer rank)
    implements AgentEventPayload {}
