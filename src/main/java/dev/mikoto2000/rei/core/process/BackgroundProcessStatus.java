package dev.mikoto2000.rei.core.process;

/** 外部プロセスの管理におけるバックグラウンドプロセス状態の選択肢。 */
public enum BackgroundProcessStatus {
  /** 起動を開始している状態。 */
  STARTING,
  /** 実行中。 */
  RUNNING,
  /** 終了済み。 */
  EXITED,
  /** 失敗。 */
  FAILED,
  /** 強制終了済み。 */
  KILLED
}
