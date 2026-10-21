package dev.mikoto2000.rei.conversation;

/**
 * 会話履歴と受付記録の管理における履歴検索対象範囲の選択肢。
 *
 * <p>Retrieval scope only; never changes conversation persistence.
 */
public enum HistorySearchScope {
  /** 現在プロジェクト限定。 */
  CURRENT_PROJECT_ONLY,
  /** 現在プロジェクトPREFERRED。 */
  CURRENT_PROJECT_PREFERRED,
  /** 全対象プロジェクト一覧。 */
  ALL_PROJECTS
}
