package dev.mikoto2000.rei.topic;

/** 話題の管理における話題Rejection理由の選択肢。 */
public enum TopicRejectionReason {
  /** LOW評価値。 */
  LOW_SCORE,
  /** LOW確信度。 */
  LOW_CONFIDENCE,
  /** 重複の有無。 */
  DUPLICATE,
  /** RECENTLY読み上げ済み。 */
  RECENTLY_SPOKEN,
  /** 最大候補一覧上限超過。 */
  MAX_CANDIDATES_EXCEEDED
}
