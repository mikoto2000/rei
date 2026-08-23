package dev.mikoto2000.rei.event;

/**
 * Agent Event の Payload の共通マーカー。
 *
 * <p>各イベント種別ごとに型安全な record を定義する。巨大な Map 中心の設計は避ける。</p>
 */
public sealed interface AgentEventPayload
    permits AgentRunStartedPayload, AgentRunCompletedPayload, AgentRunFailedPayload,
        MessageStartedPayload, MessageDeltaPayload, MessageCompletedPayload,
        ToolStartedPayload, ToolCompletedPayload, ToolFailedPayload,
        SkillSelectionStartedPayload, SkillSelectionCompletedPayload, SkillSelectionFailedPayload,
        TaskCreatedPayload, TaskStartedPayload, TaskCompletedPayload, TaskFailedPayload,
        WorkingSetItemAddedPayload, WorkingSetItemRemovedPayload,
        WorkingSetSearchStartedPayload, WorkingSetSearchCompletedPayload,
        ContextSnapshotUpdatedPayload,
        FileCreatedPayload, FileModifiedPayload, FileDeletedPayload {
}
