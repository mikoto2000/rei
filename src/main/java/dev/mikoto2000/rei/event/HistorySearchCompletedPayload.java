package dev.mikoto2000.rei.event;

/**
 * 実行状況の通知で受け渡す履歴検索完了通知内容を表す。
 *
 * <p>Counts only: no query, conversation content, or repository-local paths.
 *
 * @param preferredProjectId preferred所属プロジェクトの識別子
 * @param scope 対象範囲
 * @param searchedProjectCount searchedプロジェクト件数
 * @param currentProjectHitCount 現在プロジェクト検索結果件数
 * @param crossProjectHitCount crossプロジェクト検索結果件数
 */
public record HistorySearchCompletedPayload(
    String preferredProjectId,
    String scope,
    int searchedProjectCount,
    int currentProjectHitCount,
    int crossProjectHitCount)
    implements AgentEventPayload {}
