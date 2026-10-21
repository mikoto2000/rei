package dev.mikoto2000.rei.event;

/**
 * 実行状況の通知で受け渡すバックグラウンドプロセス失敗通知内容を表す。
 *
 * @param processId プロセス識別子
 * @param command コマンド
 * @param error エラー
 */
public record BackgroundProcessFailedPayload(
    String processId, String command, ErrorInformation error) implements AgentEventPayload {}
