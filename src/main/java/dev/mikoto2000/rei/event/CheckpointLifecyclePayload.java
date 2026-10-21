package dev.mikoto2000.rei.event;

/**
 * 実行状況の通知で受け渡す再開用の記録受付から終了までの管理通知内容を表す。
 *
 * @param taskId タスク識別子
 * @param revision 改訂番号
 * @param previousRunId 前回実行の識別子
 * @param checkpointRevision 再開用の記録改訂番号
 * @param decision 判断結果
 */
public record CheckpointLifecyclePayload(
    String taskId, long revision, String previousRunId, Long checkpointRevision, String decision)
    implements AgentEventPayload {}
