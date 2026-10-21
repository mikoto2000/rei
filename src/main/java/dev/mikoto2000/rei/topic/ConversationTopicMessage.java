package dev.mikoto2000.rei.topic;

import java.time.Instant;

/**
 * 話題の管理で受け渡す会話話題メッセージを表す。
 *
 * @param role 役割
 * @param content 内容
 * @param timestamp 記録日時
 */
public record ConversationTopicMessage(String role, String content, Instant timestamp) {}
