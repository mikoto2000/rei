package dev.mikoto2000.rei.topic;

import java.time.Instant;

/**
 * 話題の管理で受け渡す話題メッセージ文脈を表す。
 *
 * @param currentTime 現在時刻
 */
public record TopicMessageContext(Instant currentTime) {}
