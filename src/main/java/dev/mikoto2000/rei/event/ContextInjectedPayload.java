package dev.mikoto2000.rei.event;

/**
 * 実行状況の通知で受け渡す文脈追加された通知内容を表す。
 *
 * @param source 取得元
 * @param itemCount 項目件数
 * @param contextCharacters 文脈文字数
 */
public record ContextInjectedPayload(String source, Integer itemCount, int contextCharacters)
    implements AgentEventPayload {}
