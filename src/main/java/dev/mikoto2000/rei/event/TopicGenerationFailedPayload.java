package dev.mikoto2000.rei.event;

import dev.mikoto2000.rei.topic.TopicGenerationStage;
import java.time.Instant;

/**
 * 実行状況の通知で受け渡す話題生成失敗通知内容を表す。
 *
 * @param topicGenerationId 話題生成識別子
 * @param stage 段階
 * @param errorSummary エラー要約
 * @param failedAt 失敗日時
 */
public record TopicGenerationFailedPayload(
    String topicGenerationId, TopicGenerationStage stage, String errorSummary, Instant failedAt)
    implements AgentEventPayload {}
