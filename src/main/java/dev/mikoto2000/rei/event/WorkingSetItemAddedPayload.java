package dev.mikoto2000.rei.event;

/**
 * Working Set へのアイテム追加。
 *
 * @param itemId アイテムの ID
 * @param kind アイテムの種別（file など）
 * @param identifier アイテムの識別子
 * @param path アイテムのパス（任意）
 * @param reason 追加理由
 */
public record WorkingSetItemAddedPayload(String itemId, String kind, String identifier, String path, String reason)
    implements AgentEventPayload {
}
