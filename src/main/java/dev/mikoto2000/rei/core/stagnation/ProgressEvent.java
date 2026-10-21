package dev.mikoto2000.rei.core.stagnation;

/** 進展を表す軽量な内部イベント。 */
public enum ProgressEvent {
  /** 新規情報。 */
  NEW_INFORMATION,
  /** 情報不明。 */
  INFORMATION_UNKNOWN,
  /** 状態変更済み。 */
  STATE_CHANGED,
  /** SUBGOAL完了。 */
  SUBGOAL_COMPLETED,
  /** エラー確定した。 */
  ERROR_RESOLVED,
  /** 手順完了。 */
  STEP_COMPLETED,
  /** 手順変更済み。 */
  STEP_CHANGED,
  /** ファイル変更済み。 */
  FILE_CHANGED,
  /** タスク完了項目追加済み。 */
  TASK_COMPLETED_ITEM_ADDED,
  /** 新規関連するファイル。 */
  NEW_RELEVANT_FILE,
  /** 失敗情報。 */
  FAILURE,
  /** ツール呼び出し。 */
  TOOL_CALL
}
