package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.application.run.RunSnapshot;

/**
 * Web APIによる操作で受け渡す実行応答を表す。
 *
 * @param runId 実行の識別子
 * @param status 状態
 * @param sessionId 会話の識別子
 * @param turnId やり取りの識別子
 * @param projectId 所属プロジェクトの識別子
 * @param startedAt 開始日時
 * @param completedAt 完了日時
 * @param failure 失敗情報
 * @param mode 動作モード
 * @param cancellation 停止要求と実際の終了の診断情報
 */
public record RunResponse(
    String runId,
    String status,
    String sessionId,
    String turnId,
    String projectId,
    String startedAt,
    String completedAt,
    Failure failure,
    String mode,
    dev.mikoto2000.rei.core.service.CommandCancellationService.Diagnostic cancellation) {
  /** 既存の Java 呼び出し元のコンストラクターを維持する。 */
  public RunResponse(String runId, String status, String sessionId, String turnId,
      String projectId, String startedAt, String completedAt, Failure failure, String mode) {
    this(runId, status, sessionId, turnId, projectId, startedAt, completedAt, failure, mode, null);
  }
  /**
   * Web APIによる操作で受け渡す失敗情報を表す。
   *
   * @param type 種別
   * @param message メッセージ
   */
  public record Failure(String type, String message) {}

  /** Web APIによる操作で利用する情報を、受け取った形式から変換する。 */
  public static RunResponse from(RunSnapshot run) {
    return from(run, null);
  }

  /** 既存のレスポンス項目を維持し、停止進行状況を追加する。 */
  public static RunResponse from(RunSnapshot run,
      dev.mikoto2000.rei.core.service.CommandCancellationService.Diagnostic cancellation) {
    var context = run.context();
    boolean conversation = !context.conversationId().isEmpty();
    return new RunResponse(
        context.runId(),
        run.status().name(),
        conversation ? context.conversationId() : null,
        conversation ? context.runId() : null,
        context.projectId(),
        run.startedAt() == null ? null : run.startedAt().toString(),
        run.completedAt() == null ? null : run.completedAt().toString(),
        run.failure() == null ? null : new Failure(run.failure().type(), run.failure().message()),
        context.mode().name(), cancellation);
  }
}
