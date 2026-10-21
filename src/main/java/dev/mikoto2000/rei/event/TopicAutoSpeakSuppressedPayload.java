package dev.mikoto2000.rei.event;

import java.time.Instant;

/**
 * 実行状況の通知で受け渡す話題自動読み上げ抑制された通知内容を表す。
 *
 * @param reason 理由
 * @param suppressedAt 抑制された日時
 */
public record TopicAutoSpeakSuppressedPayload(String reason, Instant suppressedAt)
    implements AgentEventPayload {}
