package dev.mikoto2000.rei.event;

/**
 * 実行状況の通知で受け渡すバックグラウンドプロセス開始通知内容を表す。
 *
 * @param processId プロセス識別子
 * @param pid プロセス識別子
 * @param command コマンド
 * @param workingDirectory 作業ディレクトリ
 */
public record BackgroundProcessStartedPayload(
    String processId, long pid, String command, String workingDirectory)
    implements AgentEventPayload {}
