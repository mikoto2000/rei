package dev.mikoto2000.rei.event;

/**
 * Tool 呼び出しの失敗。
 *
 * @param toolCallId Tool 呼び出しの ID
 * @param toolName Tool 名
 * @param error エラー情報
 */
public record ToolFailedPayload(String toolCallId, String toolName, ErrorInformation error)
    implements AgentEventPayload {
}
