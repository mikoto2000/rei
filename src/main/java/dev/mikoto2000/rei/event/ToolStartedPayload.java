package dev.mikoto2000.rei.event;

/**
 * Tool 呼び出しの開始。
 *
 * <p>arguments の raw 全体ではなく、UI 表示に必要な summary を基本とする。
 * 機密情報がイベントに露出しないよう、summary は redact 済みのものを利用する。</p>
 *
 * @param toolCallId Tool 呼び出しの ID
 * @param toolName Tool 名
 * @param argumentsSummary 引数の要約
 * @param summary 追加の要約情報
 */
public record ToolStartedPayload(String toolCallId, String toolName, String argumentsSummary, String summary)
    implements AgentEventPayload {
}
