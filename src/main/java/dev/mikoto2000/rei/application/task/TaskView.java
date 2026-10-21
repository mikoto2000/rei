package dev.mikoto2000.rei.application.task;

import java.time.Instant;
import java.util.List;

/**
 * 実行中タスクの操作で受け渡すタスク表示内容を表す。
 *
 * <p>Transport projection only; execution and transitions belong to the referenced source.
 *
 * @param id 識別子
 * @param kind 種類
 * @param sourceId 取得元識別子
 * @param projectId 所属プロジェクトの識別子
 * @param sessionId 会話の識別子
 * @param runId 実行の識別子
 * @param status 状態
 * @param mode 動作モード
 * @param startedAt 開始日時
 * @param updatedAt 更新日時
 * @param waitingReason 待機中理由
 * @param errorSummary エラー要約
 * @param progress 進捗
 * @param results 結果一覧
 * @param goalId 目標識別子
 * @param dependencyIds 依存条件識別子一覧
 * @param schedulerId 実行予定の管理識別子
 * @param parentId 親の識別子
 * @param childIds 子識別子一覧
 * @param checkpointTaskId 再開用の記録タスク識別子
 * @param cancelSupported キャンセル対応済み
 * @param resumeSupported 再開対応済み
 * @param inputSupported 入力対応済み
 * @param revision 改訂番号
 */
public record TaskView(
    String id,
    String kind,
    String sourceId,
    String projectId,
    String sessionId,
    String runId,
    String status,
    String mode,
    Instant startedAt,
    Instant updatedAt,
    String waitingReason,
    String errorSummary,
    Progress progress,
    List<Reference> results,
    String goalId,
    List<String> dependencyIds,
    String schedulerId,
    String parentId,
    List<String> childIds,
    String checkpointTaskId,
    boolean cancelSupported,
    boolean resumeSupported,
    boolean inputSupported,
    long revision) {
  /**
   * 実行中タスクの操作で受け渡す進捗を表す。
   *
   * @param completed 完了
   * @param total 合計
   */
  public record Progress(int completed, int total) {}

  /**
   * 実行中タスクの操作で受け渡す参照先を表す。
   *
   * @param kind 種類
   * @param id 識別子
   */
  public record Reference(String kind, String id) {}

  /** 指定された結果一覧を反映した情報を作成する。 */
  public TaskView withResults(List<Reference> references) {
    return new TaskView(
        id,
        kind,
        sourceId,
        projectId,
        sessionId,
        runId,
        status,
        mode,
        startedAt,
        updatedAt,
        waitingReason,
        errorSummary,
        progress,
        references,
        goalId,
        dependencyIds,
        schedulerId,
        parentId,
        childIds,
        checkpointTaskId,
        cancelSupported,
        resumeSupported,
        inputSupported,
        revision);
  }

  /** 受け取った情報からTaskViewを作成する。 */
  public TaskView {
    results = List.copyOf(results);
    dependencyIds = List.copyOf(dependencyIds);
    childIds = List.copyOf(childIds);
  }

  /** 実行中タスクの操作において、中断対応済みに該当するか判定する。 */
  @com.fasterxml.jackson.annotation.JsonProperty("suspendSupported")
  public boolean suspendSupported() {
    return cancelSupported
        && runId != null
        && ("CHECKPOINT".equals(kind) || "GOAL".equals(kind) && "RUNNING".equals(status));
  }

  /** 実行中タスクの操作で用いるリンク一覧を返す。 */
  public TaskView links(
      String goal,
      List<String> dependencies,
      String scheduler,
      String parent,
      List<String> children) {
    return new TaskView(
        id,
        kind,
        sourceId,
        projectId,
        sessionId,
        runId,
        status,
        mode,
        startedAt,
        updatedAt,
        waitingReason,
        errorSummary,
        progress,
        results,
        goal,
        dependencies,
        scheduler,
        parent,
        children,
        checkpointTaskId,
        cancelSupported,
        resumeSupported,
        inputSupported,
        revision);
  }
}
