package dev.mikoto2000.rei.topic;

/** 話題の管理における話題読み上げ対象外理由の選択肢。 */
public enum TopicSpeakSkipReason {
  /** 通知を控える期間。 */
  COOLDOWN,
  /** 利用者活動中。 */
  USER_ACTIVE,
  /** エージェント使用中。 */
  AGENT_BUSY,
  /** 未満判定の境界値。 */
  BELOW_THRESHOLD,
  /** LOW確信度。 */
  LOW_CONFIDENCE,
  /** NO候補。 */
  NO_CANDIDATE,
  /** 機能無効状態。 */
  FEATURE_DISABLED
}
