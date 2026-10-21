package dev.mikoto2000.rei.event;

import java.time.Instant;

/**
 * 実行状況の通知で受け渡す話題生成開始通知内容を表す。
 *
 * @param topicGenerationId 話題生成識別子
 * @param trigger 実行条件
 * @param startedAt 開始日時
 */
public record TopicGenerationStartedPayload(
    String topicGenerationId, String trigger, Instant startedAt) implements AgentEventPayload {}
