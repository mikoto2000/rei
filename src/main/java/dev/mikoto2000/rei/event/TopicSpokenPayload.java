package dev.mikoto2000.rei.event;

import java.time.Instant;

/**
 * 実行状況の通知で受け渡す話題読み上げ済み通知内容を表す。
 *
 * @param topicGenerationId 話題生成識別子
 * @param candidateId 候補識別子
 * @param messageId メッセージ識別子
 * @param spokenAt 読み上げ済み日時
 * @param messageSummary メッセージ要約
 */
public record TopicSpokenPayload(
    String topicGenerationId,
    String candidateId,
    String messageId,
    Instant spokenAt,
    String messageSummary)
    implements AgentEventPayload {}
