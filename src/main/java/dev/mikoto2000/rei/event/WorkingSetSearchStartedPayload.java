package dev.mikoto2000.rei.event;

/**
 * 実行状況の通知で受け渡す作業中集合検索開始通知内容を表す。
 *
 * <p>Working Set search lifecycle start; query is bounded by the publisher.
 *
 * @param searchId 検索識別子
 * @param query 検索条件
 * @param strategy 方針
 * @param workingSetSizeBefore 作業中集合大きさ変更前
 */
public record WorkingSetSearchStartedPayload(
    String searchId, String query, String strategy, int workingSetSizeBefore)
    implements AgentEventPayload {}
