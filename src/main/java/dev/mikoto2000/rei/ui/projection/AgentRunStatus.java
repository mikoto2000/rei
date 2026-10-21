package dev.mikoto2000.rei.ui.projection;

/** 実行状況の表示用整理におけるエージェント実行状態の選択肢。 */
public enum AgentRunStatus {
  /** 操作がない状態。 */
  IDLE,
  /** 実行中。 */
  RUNNING,
  /** 完了。 */
  COMPLETED,
  /** 失敗。 */
  FAILED,
  /** キャンセル済み。 */
  CANCELLED
}
