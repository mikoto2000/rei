package dev.mikoto2000.rei.core.execution;

import java.time.Instant;

/**
 * 処理の実行制御で受け渡す完了実行を表す。
 *
 * @param id 識別子
 * @param projectId 所属プロジェクトの識別子
 * @param conversationId 会話の識別子
 * @param type 種別
 * @param source 取得元
 * @param result 結果
 * @param startedAt 開始日時
 * @param completedAt 完了日時
 */
public record CompletedExecution(
    String id,
    String projectId,
    String conversationId,
    ExecutionType type,
    String source,
    String result,
    Instant startedAt,
    Instant completedAt) {
  /** 処理の実行制御で受け渡す情報を、指定された内容から作成する。 */
  public static CompletedExecution of(
      ActiveExecution execution, String source, String result, Instant completedAt) {
    return new CompletedExecution(
        execution.id(),
        execution.projectId(),
        execution.conversationId(),
        execution.type(),
        source,
        result,
        execution.startedAt(),
        completedAt);
  }
}
