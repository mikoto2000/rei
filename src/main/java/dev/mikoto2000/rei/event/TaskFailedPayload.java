package dev.mikoto2000.rei.event;

/**
 * タスクの失敗。
 *
 * @param taskId タスクの ID
 * @param error エラー情報
 */
public record TaskFailedPayload(String taskId, ErrorInformation error)
    implements AgentEventPayload {
}
