package dev.mikoto2000.rei.event;

/**
 * 実行状況の通知で受け渡すファイル要約Invalidated通知内容を表す。
 *
 * @param path パス
 */
public record FileSummaryInvalidatedPayload(String path) implements AgentEventPayload {}
