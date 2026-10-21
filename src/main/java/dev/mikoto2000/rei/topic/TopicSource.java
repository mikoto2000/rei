package dev.mikoto2000.rei.topic;

/** 話題の管理における話題取得元の選択肢。 */
public enum TopicSource {
  /** 作業中集合。 */
  WORKING_SET,
  /** 会話。 */
  CONVERSATION,
  /** 記憶。 */
  MEMORY,
  /** 情報への関心実行待ち。 */
  CURIOSITY_QUEUE,
  /** Web経由の入力または調査に使う窓口。 */
  WEB,
  /** フィード。 */
  FEED,
  /** GitHubの利用設定または連携情報の保存先。 */
  GITHUB,
  /** 時刻文脈。 */
  TIME_CONTEXT
}
