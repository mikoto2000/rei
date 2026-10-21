package dev.mikoto2000.rei.core.dependency;

/** 外部条件の待機における依存条件状態の選択肢。 */
public enum DependencyState {
  /** 実行中。 */
  RUNNING,
  /** 待機中。 */
  WAITING,
  /** 実行を妨げる条件あり。 */
  BLOCKED,
  /** 完了。 */
  COMPLETED,
  /** 失敗。 */
  FAILED,
  /** キャンセル済み。 */
  CANCELLED
}
