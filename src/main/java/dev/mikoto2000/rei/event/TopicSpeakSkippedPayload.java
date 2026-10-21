package dev.mikoto2000.rei.event;

import dev.mikoto2000.rei.topic.TopicSpeakSkipReason;
import java.time.Instant;

/**
 * 実行状況の通知で受け渡す話題読み上げ対象外通知内容を表す。
 *
 * @param topicGenerationId 話題生成識別子
 * @param candidateId 候補識別子
 * @param reason 理由
 * @param nextSpeakAllowedAt 次読み上げ許可日時
 */
public record TopicSpeakSkippedPayload(
    String topicGenerationId,
    String candidateId,
    TopicSpeakSkipReason reason,
    Instant nextSpeakAllowedAt)
    implements AgentEventPayload {}
