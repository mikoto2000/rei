package dev.mikoto2000.rei.ui.projection;

import java.time.Instant;

/**
 * 実行状況の表示用整理で受け渡すツール実行表示内容を表す。
 *
 * @param toolCallId ツール呼び出し識別子
 * @param toolName ツール名
 * @param status 状態
 * @param argumentsSummary 引数要約
 * @param resultSummary 結果要約
 * @param error エラー
 * @param durationMillis 継続時間ミリ秒数
 * @param startedAt 開始日時
 * @param completedAt 完了日時
 */
public record ToolExecutionView(
    String toolCallId,
    String toolName,
    ToolExecutionStatus status,
    String argumentsSummary,
    String resultSummary,
    ErrorView error,
    Long durationMillis,
    Instant startedAt,
    Instant completedAt) {}
