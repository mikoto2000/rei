package dev.mikoto2000.rei.event;

/**
 * Tool 呼び出しの完了。
 *
 * <p>Tool の結果全文を Event に格納しない。要約情報（件数・バイト数など）を優先する。</p>
 *
 * @param toolCallId Tool 呼び出しの ID
 * @param toolName Tool 名
 * @param duration 実行時間（ミリ秒）
 * @param resultSummary 結果の要約
 * @param files 対象ファイル数（任意）
 * @param bytes 対象バイト数（任意）
 * @param matches 検索ヒット数（任意）
 * @param items アイテム数（任意）
 */
public record ToolCompletedPayload(
    String toolCallId,
    String toolName,
    long duration,
    String resultSummary,
    Integer files,
    Long bytes,
    Integer matches,
    Integer items)
    implements AgentEventPayload {
}
