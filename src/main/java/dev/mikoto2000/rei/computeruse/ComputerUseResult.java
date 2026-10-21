package dev.mikoto2000.rei.computeruse;

/**
 * 画面操作の支援で受け渡す画面操作利用結果を表す。
 *
 * @param status 状態
 * @param steps 手順一覧
 * @param reason 理由
 */
public record ComputerUseResult(Status status, int steps, String reason) {
  /** 画面操作の支援における状態の選択肢。 */
  public enum Status {
    /** 完了。 */
    DONE,
    /** 失敗。 */
    FAILED,
    /** 最大手順一覧。 */
    MAX_STEPS,
    /** キャンセル済み。 */
    CANCELLED,
    /** モデルエラー。 */
    MODEL_ERROR,
    /** 取得記録エラー。 */
    CAPTURE_ERROR,
    /** 操作エラー。 */
    ACTION_ERROR,
    /** STABILIZATIONエラー。 */
    STABILIZATION_ERROR,
    /** SAFETY実行を妨げる条件あり。 */
    SAFETY_BLOCKED,
    /** 使用中。 */
    BUSY
  }
}
