package dev.mikoto2000.rei.interest;

import java.time.OffsetDateTime;

/**
 * 関心の管理で受け渡す会話抜粋を表す。
 *
 * @param conversationId 会話の識別子
 * @param text テキスト
 * @param createdAt 作成日時
 */
public record ConversationSnippet(String conversationId, String text, OffsetDateTime createdAt) {}
