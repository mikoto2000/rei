package dev.mikoto2000.rei.event;

/**
 * コンテキストスナップショットの更新。
 *
 * @param usedTokens 使用トークン数
 * @param maxTokens 最大トークン数
 * @param utilization 利用率（0.0〜1.0、任意）
 */
public record ContextSnapshotUpdatedPayload(Long usedTokens, Long maxTokens, Double utilization)
    implements AgentEventPayload {
}
