package dev.mikoto2000.rei.topic;

/** 話題の管理における話題生成状態の選択肢。 */
public enum TopicGenerationStatus {
  /** 操作がない状態。 */
  IDLE,
  /** 内容を生成している状態。 */
  GENERATING,
  /** 内容を評価している状態。 */
  EVALUATING,
  /** 準備完了。 */
  READY,
  /** 対象外。 */
  SKIPPED,
  /** 読み上げ済み。 */
  SPOKEN,
  /** 完了。 */
  COMPLETED,
  /** 失敗。 */
  FAILED
}
