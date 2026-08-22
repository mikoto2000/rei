package dev.mikoto2000.rei.event;

/**
 * メッセージ生成の開始。
 *
 * @param messageId メッセージの ID
 * @param role メッセージのロール
 */
public record MessageStartedPayload(String messageId, String role)
    implements AgentEventPayload {
}
