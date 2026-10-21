package dev.mikoto2000.rei.topic;

/** 話題の管理における話題候補状態の選択肢。 */
public enum TopicCandidateStatus {
  /** 生成済み。 */
  GENERATED,
  /** 評価済み。 */
  SCORED,
  /** 拒否済み。 */
  REJECTED,
  /** 選択中。 */
  SELECTED,
  /** 読み上げ済み。 */
  SPOKEN
}
