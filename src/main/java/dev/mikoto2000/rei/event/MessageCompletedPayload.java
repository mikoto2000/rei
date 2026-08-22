package dev.mikoto2000.rei.event;

/**
 * メッセージ生成の完了。
 *
 * @param messageId メッセージの ID
 * @param role メッセージのロール
 * @param text 最終テキスト
 */
public record MessageCompletedPayload(String messageId, String role, String text)
    implements AgentEventPayload {
}
