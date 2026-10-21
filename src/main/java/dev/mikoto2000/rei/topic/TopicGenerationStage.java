package dev.mikoto2000.rei.topic;

/** 話題の管理における話題生成段階の選択肢。 */
public enum TopicGenerationStage {
  /** 候補生成。 */
  CANDIDATE_GENERATION,
  /** 順位付け。 */
  RANKING,
  /** 読み上げ判断結果。 */
  SPEAK_DECISION,
  /** メッセージ生成。 */
  MESSAGE_GENERATION,
  /** 利用先の検出。 */
  DISCOVERY
}
