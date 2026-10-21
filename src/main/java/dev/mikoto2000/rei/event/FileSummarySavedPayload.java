package dev.mikoto2000.rei.event;

/**
 * 実行状況の通知で受け渡すファイル要約保存済み通知内容を表す。
 *
 * @param path パス
 * @param summaryCharacters 要約文字数
 */
public record FileSummarySavedPayload(String path, int summaryCharacters)
    implements AgentEventPayload {}
