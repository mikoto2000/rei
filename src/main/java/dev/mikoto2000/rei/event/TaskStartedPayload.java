package dev.mikoto2000.rei.event;

/**
 * タスクの開始。
 *
 * @param taskId タスクの ID
 */
public record TaskStartedPayload(String taskId)
    implements AgentEventPayload {
}
