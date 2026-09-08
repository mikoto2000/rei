package dev.mikoto2000.rei.core.stagnation;

/**
 * 進展を表す軽量な内部イベント。
 */
public enum ProgressEvent {
  NEW_INFORMATION,
  STATE_CHANGED,
  SUBGOAL_COMPLETED,
  ERROR_RESOLVED,
  STEP_COMPLETED,
  STEP_CHANGED,
  FILE_CHANGED,
  TASK_COMPLETED_ITEM_ADDED,
  NEW_RELEVANT_FILE,
  FAILURE,
  TOOL_CALL
}
