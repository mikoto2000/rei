package dev.mikoto2000.rei.event;

/**
 * Working Set からのアイテム除去。
 *
 * @param itemId アイテムの ID
 * @param reason 除去理由
 */
public record WorkingSetItemRemovedPayload(String itemId, String reason)
    implements AgentEventPayload {
}
