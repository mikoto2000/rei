package dev.mikoto2000.rei.event;

/**
 * 実行状況の通知で受け渡すバックグラウンドプロセス完了通知内容を表す。
 *
 * @param processId プロセス識別子
 * @param pid プロセス識別子
 * @param exitCode 終了コード
 * @param elapsedSeconds 経過時間秒数
 */
public record BackgroundProcessCompletedPayload(
    String processId, long pid, Integer exitCode, double elapsedSeconds)
    implements AgentEventPayload {}
