package dev.mikoto2000.rei.event;

/**
 * 実行状況の通知で受け渡す話題候補生成済み通知内容を表す。
 *
 * @param topicGenerationId 話題生成識別子
 * @param candidateId 候補識別子
 * @param topicType 話題種別
 * @param source 取得元
 * @param topicSummary 話題要約
 * @param reasonSummary 理由要約
 * @param priority 優先度
 * @param freshness 情報の鮮度
 * @param usefulness 有用性
 * @param intrusiveness 割り込みの負担
 * @param confidence 確信度
 */
public record TopicCandidateGeneratedPayload(
    String topicGenerationId,
    String candidateId,
    String topicType,
    String source,
    String topicSummary,
    String reasonSummary,
    Double priority,
    Double freshness,
    Double usefulness,
    Double intrusiveness,
    Double confidence)
    implements AgentEventPayload {}
