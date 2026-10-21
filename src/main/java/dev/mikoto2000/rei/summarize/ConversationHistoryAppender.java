package dev.mikoto2000.rei.summarize;

/** 情報の要約に必要な会話履歴Appenderを扱う。 */
public interface ConversationHistoryAppender {

  /** 既存の記録へ経緯を残すため、利用者メッセージを追記する。 */
  void appendUserMessage(String content);

  /** 既存の記録へ経緯を残すため、アシスタントメッセージを追記する。 */
  void appendAssistantMessage(String content);
}
