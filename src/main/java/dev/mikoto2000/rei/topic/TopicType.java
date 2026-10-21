package dev.mikoto2000.rei.topic;

/** 話題の管理における話題種別の選択肢。 */
public enum TopicType {
  /** UNFINISHED作業。 */
  UNFINISHED_WORK,
  /** 後続の入力。 */
  FOLLOW_UP,
  /** 直近関心。 */
  RECENT_INTEREST,
  /** 利用先の検出。 */
  DISCOVERY,
  /** 振り返り。 */
  REFLECTION,
  /** 雑談として扱う話題。 */
  CASUAL,
  /** 時刻文脈。 */
  TIME_CONTEXT
}
