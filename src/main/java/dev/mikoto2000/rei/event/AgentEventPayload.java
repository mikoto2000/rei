package dev.mikoto2000.rei.event;

/**
 * Agent Event の Payload の共通マーカー。
 *
 * <p>各イベント種別ごとに型安全な record を定義する。巨大な Map 中心の設計は避ける。</p>
 */
public sealed interface AgentEventPayload
    permits AgentRunStartedPayload, AgentRunCompletedPayload, AgentRunFailedPayload,
        LlmRequestStartedPayload, LlmResponseCompletedPayload,
        MessageStartedPayload, MessageDeltaPayload, MessageCompletedPayload,
        MemoryConsolidationSuggestedPayload,
        ThinkingStartedPayload, ThinkingDeltaPayload, ThinkingCompletedPayload,
        ToolStartedPayload, ToolCompletedPayload, ToolFailedPayload,
        SkillSelectionStartedPayload, SkillSelectionCompletedPayload, SkillSelectionFailedPayload,
        SkillRoutingStartedPayload, SkillRoutingCompletedPayload, SkillRoutingFailedPayload,
        SkillCandidatesEvaluatedPayload,
        TaskCreatedPayload, TaskStartedPayload, TaskCompletedPayload, TaskFailedPayload,
        WorkingSetItemAddedPayload, WorkingSetItemRemovedPayload,
        WorkingSetSearchStartedPayload, WorkingSetSearchCompletedPayload,
        TopicGenerationStartedPayload, TopicIdleTriggerEvaluatedPayload, TopicCandidatesRefreshedPayload,
        TopicCandidateGeneratedPayload, TopicCandidateScoredPayload,
        TopicCandidateRejectedPayload, TopicSelectedPayload, TopicSpeakSkippedPayload, TopicSpokenPayload,
        TopicGenerationCompletedPayload, TopicGenerationFailedPayload, TopicAutoSpeakSuppressedPayload,
        ContextSnapshotUpdatedPayload,
        FileCreatedPayload, FileModifiedPayload, FileDeletedPayload {
}
