package dev.mikoto2000.rei.event;

/**
 * タスクの作成。
 *
 * @param taskId タスクの ID
 * @param parentTaskId 親タスクの ID（任意）
 * @param title タスクのタイトル
 * @param status タスクの状態
 */
public record TaskCreatedPayload(String taskId, String parentTaskId, String title, String status)
    implements AgentEventPayload {
}
