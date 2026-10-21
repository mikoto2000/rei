package dev.mikoto2000.rei.activity;

import java.time.Instant;
import java.util.List;

/**
 * 作業状況の把握で受け渡す作業作業状況推定を表す。
 *
 * <p>Supplemental inference, never an observation interval or a claim of user execution.
 *
 * @param id 識別子
 * @param inputHash 入力ハッシュ値
 * @param inferredAt 推定した日時
 * @param windowStart 範囲開始
 * @param windowEnd 範囲終了
 * @param projectId 所属プロジェクトの識別子
 * @param project プロジェクト
 * @param inferredActivity 推定した作業状況
 * @param observationIds 観測内容識別子一覧
 * @param executions 実行一覧
 * @param confidence 確信度
 * @param status 状態
 * @param method メソッド
 * @param llmCalls 言語モデル呼び出し回数
 * @param totalTokens 使用トークン数の合計
 */
public record WorkActivityInference(
    String id,
    String inputHash,
    Instant inferredAt,
    Instant windowStart,
    Instant windowEnd,
    String projectId,
    String project,
    String inferredActivity,
    List<String> observationIds,
    List<Execution> executions,
    double confidence,
    String status,
    String method,
    int llmCalls,
    long totalTokens) {
  /**
   * 作業状況の把握で受け渡す実行を表す。
   *
   * @param eventId イベントの識別子
   * @param at 日時
   * @param projectId 所属プロジェクトの識別子
   * @param kind 種類
   * @param actor 操作の主体
   * @param outcome 最終結果
   * @param resultSummary 結果要約
   * @param sessionId 会話の識別子
   * @param turnId やり取りの識別子
   * @param runId 実行の識別子
   */
  public record Execution(
      String eventId,
      Instant at,
      String projectId,
      String kind,
      String actor,
      String outcome,
      String resultSummary,
      String sessionId,
      String turnId,
      String runId) {}

  /** 受け取った情報からWorkActivityInferenceを作成する。 */
  public WorkActivityInference {
    observationIds = List.copyOf(observationIds);
    executions = List.copyOf(executions);
    if (id == null
        || inputHash == null
        || inferredAt == null
        || windowStart == null
        || windowEnd == null
        || windowStart.isAfter(windowEnd)
        || !Double.isFinite(confidence)
        || confidence < 0
        || confidence > .8
        || inferredActivity == null
        || inferredActivity.length() > 600
        || observationIds.size() > 120
        || executions.size() > 64
        || llmCalls < 0
        || totalTokens < 0) throw new IllegalArgumentException("Invalid temporal inference");
    if (executions.stream().anyMatch(e -> !"REI".equals(e.actor())))
      throw new IllegalArgumentException("Unsupported execution actor");
  }
}
