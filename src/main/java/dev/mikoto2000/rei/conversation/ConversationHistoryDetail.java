package dev.mikoto2000.rei.conversation;

import java.util.List;

/**
 * 会話履歴と受付記録の管理で受け渡す会話履歴詳細を表す。
 *
 * @param conversationId 会話の識別子
 * @param scope 対象範囲
 * @param messages メッセージ一覧
 * @param sourceProjectId 取得元所属プロジェクトの識別子
 * @param sourceProjectName 取得元プロジェクト名前
 * @param contextBoundary 文脈境界
 */
public record ConversationHistoryDetail(
    String conversationId,
    String scope,
    List<ConversationHistoryMessage> messages,
    String sourceProjectId,
    String sourceProjectName,
    String contextBoundary) {
  /** 会話履歴と受付記録の管理を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public ConversationHistoryDetail(
      String conversationId, String scope, List<ConversationHistoryMessage> messages) {
    this(conversationId, scope, messages, null, null, null);
  }
}
