package dev.mikoto2000.rei.conversation;

/**
 * 会話履歴と受付記録の管理で受け渡す会話履歴メッセージを表す。
 *
 * @param speaker 読み上げ先
 * @param timestamp 記録日時
 * @param content 内容
 */
public record ConversationHistoryMessage(String speaker, String timestamp, String content) {}
