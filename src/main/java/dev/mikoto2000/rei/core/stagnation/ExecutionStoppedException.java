package dev.mikoto2000.rei.core.stagnation;

/**
 * 実行の停滞への対処を継続できない実行停止済みの状況を伝える例外。
 *
 * <p>Control signal, distinguished from model/transport failures at the run boundary.
 */
public class ExecutionStoppedException extends RuntimeException {
  /** 実行の停滞への対処における理由の選択肢。 */
  public enum Reason {
    /** 進捗が得られず停滞した状態。 */
    STAGNATED,
    /** 言語モデル呼び出し予算上限超過。 */
    LLM_CALL_BUDGET_EXCEEDED,
    /** 計画の見直し予算上限超過。 */
    REPLAN_BUDGET_EXCEEDED,
    /** 文脈厳しい条件上限。 */
    CONTEXT_HARD_LIMIT,
    /** レビュー部分的な結果。 */
    REVIEW_PARTIAL,
    /** トークン予算上限超過。 */
    TOKEN_BUDGET_EXCEEDED,
    /** トークン使用状況不明。 */
    TOKEN_USAGE_UNKNOWN,
    /** ツール引数修復残量なし。 */
    TOOL_ARGUMENT_REPAIR_EXHAUSTED
  }

  /** 理由。 */
  private final Reason reason;

  /** 指定された値や利用先を使って、ExecutionStoppedExceptionを初期化する。 */
  public ExecutionStoppedException(Reason reason) {
    super(reason.name());
    this.reason = reason;
  }

  public ExecutionStoppedException(Reason reason, String diagnosis) {
    super(reason.name() + ": " + diagnosis);
    this.reason = reason;
  }

  /** 実行の停滞への対処で用いる理由を返す。 */
  public Reason reason() {
    return reason;
  }
}
