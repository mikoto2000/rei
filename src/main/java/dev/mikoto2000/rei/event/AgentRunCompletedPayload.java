package dev.mikoto2000.rei.event;

/**
 * Agent Run の正常終了。
 *
 * @param runId Agent Loop の一回の実行単位
 * @param duration 実行時間（ミリ秒）
 */
public record AgentRunCompletedPayload(String runId, long duration)
    implements AgentEventPayload {
}
