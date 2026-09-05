package dev.mikoto2000.rei.event;

/**
 * Working Set を LLM コンテキストへ注入した事実。
 *
 * @param itemCount 注入対象になった Working Set アイテム数
 * @param contextCharacters 注入したコンテキスト文字数
 */
public record WorkingSetContextInjectedPayload(int itemCount, int contextCharacters)
    implements AgentEventPayload {
}
