package dev.mikoto2000.rei.conversation;

/**
 * 会話履歴と受付記録の管理で受け渡す履歴検索要求を表す。
 *
 * <p>Immutable search ownership, separate from the message category (conversationScope).
 *
 * @param query 検索条件
 * @param preferredProjectId preferred所属プロジェクトの識別子
 * @param referencedProject referencedプロジェクト
 * @param retrievalScope 関連情報の取得対象範囲
 * @param conversationScope 会話対象範囲
 * @param speaker 読み上げ先
 * @param since 開始日時
 * @param until 終了日時
 * @param limit 上限
 */
public record HistorySearchRequest(
    String query,
    String preferredProjectId,
    String referencedProject,
    HistorySearchScope retrievalScope,
    String conversationScope,
    String speaker,
    String since,
    String until,
    Integer limit) {
  /** 受け取った情報からHistorySearchRequestを作成する。 */
  public HistorySearchRequest {
    if (query == null || query.isBlank())
      throw new IllegalArgumentException("query must not be blank");
    if (preferredProjectId != null) java.util.UUID.fromString(preferredProjectId);
    if (retrievalScope == null) retrievalScope = HistorySearchScope.CURRENT_PROJECT_PREFERRED;
  }
}
