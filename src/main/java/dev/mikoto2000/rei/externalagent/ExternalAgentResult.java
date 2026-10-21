package dev.mikoto2000.rei.externalagent;

import java.util.List;

/**
 * 外部エージェントへの委譲で受け渡す外部エージェント結果を表す。
 *
 * @param status 状態
 * @param summary 要約
 * @param findings 指摘一覧
 * @param warnings 注意事項
 * @param duration 継続時間
 * @param exitCode 終了コード
 * @param rawOutput 元の出力
 * @param reviewId レビュー識別子
 * @param externalSessionId 外部会話の識別子
 * @param proposedChange 提案内容変更
 * @param changeSetId 変更集合識別子
 * @param implementation 実装
 * @param materialReviewReport 教材レビューレポート
 */
public record ExternalAgentResult(
    Status status,
    String summary,
    List<ExternalAgentFinding> findings,
    List<String> warnings,
    long duration,
    Integer exitCode,
    String rawOutput,
    String reviewId,
    String externalSessionId,
    dev.mikoto2000.rei.core.TextChangeSetService.Request proposedChange,
    String changeSetId,
    ImplementationProposal implementation,
    String materialReviewReport) {
  /** 外部エージェントへの委譲を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public ExternalAgentResult(
      Status status,
      String summary,
      List<ExternalAgentFinding> findings,
      List<String> warnings,
      long duration,
      Integer exitCode,
      String rawOutput,
      String reviewId,
      String externalSessionId,
      dev.mikoto2000.rei.core.TextChangeSetService.Request proposedChange,
      String changeSetId,
      ImplementationProposal implementation) {
    this(
        status,
        summary,
        findings,
        warnings,
        duration,
        exitCode,
        rawOutput,
        reviewId,
        externalSessionId,
        proposedChange,
        changeSetId,
        implementation,
        null);
  }

  /** 外部エージェントへの委譲を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public ExternalAgentResult(
      Status status,
      String summary,
      List<ExternalAgentFinding> findings,
      List<String> warnings,
      long duration,
      Integer exitCode,
      String rawOutput,
      String reviewId,
      String externalSessionId,
      dev.mikoto2000.rei.core.TextChangeSetService.Request proposedChange,
      String changeSetId) {
    this(
        status,
        summary,
        findings,
        warnings,
        duration,
        exitCode,
        rawOutput,
        reviewId,
        externalSessionId,
        proposedChange,
        changeSetId,
        null);
  }

  /** 外部エージェントへの委譲を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public ExternalAgentResult(
      Status status,
      String summary,
      List<ExternalAgentFinding> findings,
      List<String> warnings,
      long duration,
      Integer exitCode,
      String rawOutput,
      String reviewId,
      String externalSessionId) {
    this(
        status,
        summary,
        findings,
        warnings,
        duration,
        exitCode,
        rawOutput,
        reviewId,
        externalSessionId,
        null,
        null);
  }

  /** 外部エージェントへの委譲を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public ExternalAgentResult(
      Status status,
      String summary,
      List<ExternalAgentFinding> findings,
      List<String> warnings,
      long duration,
      Integer exitCode,
      String rawOutput,
      String reviewId) {
    this(status, summary, findings, warnings, duration, exitCode, rawOutput, reviewId, null);
  }

  /** 外部エージェントへの委譲を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public ExternalAgentResult(
      Status status,
      String summary,
      List<ExternalAgentFinding> findings,
      List<String> warnings,
      long duration,
      Integer exitCode,
      String rawOutput) {
    this(status, summary, findings, warnings, duration, exitCode, rawOutput, null);
  }

  /** 外部エージェントへの委譲における状態の選択肢。 */
  public enum Status {
    /** 成功。 */
    SUCCESS,
    /** 成功付き注意事項。 */
    SUCCESS_WITH_WARNINGS,
    /** 失敗。 */
    FAILED,
    /** 利用不可。 */
    UNAVAILABLE,
    /** 合計タイムアウト。 */
    TOTAL_TIMEOUT,
    /** INACTIVITYタイムアウト。 */
    INACTIVITY_TIMEOUT,
    /** キャンセル済み。 */
    CANCELLED,
    /** 拒否済み。 */
    REJECTED
  }

  /** 受け取った情報からExternalAgentResultを作成する。 */
  public ExternalAgentResult {
    findings = List.copyOf(findings);
    warnings = List.copyOf(warnings);
    if (!validSessionId(externalSessionId)
        || (status != Status.SUCCESS && status != Status.SUCCESS_WITH_WARNINGS))
      externalSessionId = null;
    if (status != Status.SUCCESS && status != Status.SUCCESS_WITH_WARNINGS) {
      proposedChange = null;
      implementation = null;
      materialReviewReport = null;
    }
  }

  /** 外部エージェントへの委譲において、有効会話の識別子に該当するか判定する。 */
  static boolean validSessionId(String value) {
    return value != null
        && value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
  }

  /** 処理が成功したことを、呼び出し元へ伝える情報として表す。 */
  public boolean success() {
    return status == Status.SUCCESS || status == Status.SUCCESS_WITH_WARNINGS;
  }

  /** 外部エージェントへの委譲で用いる拒否済みを返す。 */
  public static ExternalAgentResult rejected(String reason) {
    return new ExternalAgentResult(Status.REJECTED, reason, List.of(), List.of(), 0, null, "");
  }

  /**
   * 外部エージェントへの委譲で用いる対象評価を返す。
   *
   * <p>Only this bounded review, never process logs, is passed into rei's ephemeral reasoning.
   */
  public ExternalAgentResult forEvaluation() {
    return new ExternalAgentResult(
        status,
        summary,
        findings,
        warnings,
        duration,
        exitCode,
        "",
        reviewId,
        externalSessionId,
        null,
        changeSetId,
        null,
        materialReviewReport);
  }
}
