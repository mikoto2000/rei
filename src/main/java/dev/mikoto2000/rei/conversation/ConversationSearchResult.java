package dev.mikoto2000.rei.conversation;

/**
 * 会話履歴と受付記録の管理で受け渡す会話検索結果を表す。
 *
 * @param conversationId 会話の識別子
 * @param scope 対象範囲
 * @param speaker 読み上げ先
 * @param timestamp 記録日時
 * @param summary 要約
 * @param content 内容
 * @param sourceProjectId 取得元所属プロジェクトの識別子
 * @param sourceProjectName 取得元プロジェクト名前
 * @param contextBoundary 文脈境界
 * @param relevanceScore 関連度評価値
 */
public record ConversationSearchResult(
    String conversationId,
    String scope,
    String speaker,
    String timestamp,
    String summary,
    String content,
    String sourceProjectId,
    String sourceProjectName,
    String contextBoundary,
    double relevanceScore) {
  /** 会話履歴と受付記録の管理を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public ConversationSearchResult(
      String conversationId,
      String scope,
      String speaker,
      String timestamp,
      String summary,
      String content) {
    this(conversationId, scope, speaker, timestamp, summary, content, null, null, null, 0);
  }
}
