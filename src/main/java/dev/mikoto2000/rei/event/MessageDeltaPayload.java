package dev.mikoto2000.rei.event;

/**
 * メッセージ生成の途中経過。
 *
 * <p>Provider 固有の delta を正規化したテキスト断片を保持する。</p>
 *
 * @param messageId メッセージの ID
 * @param delta 追加されたテキスト断片
 */
public record MessageDeltaPayload(String messageId, String delta)
    implements AgentEventPayload {
}
