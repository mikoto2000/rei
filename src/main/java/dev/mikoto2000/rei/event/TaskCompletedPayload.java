package dev.mikoto2000.rei.event;

/**
 * タスクの完了。
 *
 * @param taskId タスクの ID
 * @param duration 実行時間（ミリ秒、任意）
 */
public record TaskCompletedPayload(String taskId, Long duration)
    implements AgentEventPayload {
}
