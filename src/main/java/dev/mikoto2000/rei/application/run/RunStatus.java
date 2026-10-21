package dev.mikoto2000.rei.application.run;

/** 実行の受付と状態管理における実行状態の選択肢。 */
public enum RunStatus {
  /** 待機中。 */
  QUEUED,
  /** 実行中。 */
  RUNNING,
  /** 待機中。 */
  WAITING,
  /** 待機中承認。 */
  WAITING_APPROVAL,
  /** 完了。 */
  COMPLETED,
  /** 失敗。 */
  FAILED,
  /** キャンセル済み。 */
  CANCELLED,
  /** 不明。 */
  UNKNOWN;

  /** 実行の受付と状態管理において、待機中かどうかを判定する。 */
  public boolean isWaiting() {
    return this == WAITING || this == WAITING_APPROVAL;
  }

  /** 実行の受付と状態管理において、終端かどうかを判定する。 */
  public boolean isTerminal() {
    return this == COMPLETED || this == FAILED || this == CANCELLED || this == UNKNOWN;
  }
}
