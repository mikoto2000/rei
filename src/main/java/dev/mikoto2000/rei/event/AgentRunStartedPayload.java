package dev.mikoto2000.rei.event;

/**
 * Agent Run の開始。
 *
 * @param runId Agent Loop の一回の実行単位
 * @param reason 開始理由（任意）
 * @param parentRunId 親 Run の ID（任意）
 */
public record AgentRunStartedPayload(String runId, String reason, String parentRunId)
    implements AgentEventPayload {
}
