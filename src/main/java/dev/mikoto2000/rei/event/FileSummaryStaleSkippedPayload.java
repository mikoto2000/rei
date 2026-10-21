package dev.mikoto2000.rei.event;

/**
 * 実行状況の通知で受け渡すファイル要約古い状態対象外通知内容を表す。
 *
 * @param path パス
 */
public record FileSummaryStaleSkippedPayload(String path) implements AgentEventPayload {}
