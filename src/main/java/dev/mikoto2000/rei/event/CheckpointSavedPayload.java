package dev.mikoto2000.rei.event;

/**
 * 実行状況の通知で受け渡す再開用の記録保存済み通知内容を表す。
 *
 * @param taskId タスク識別子
 * @param reason 理由
 * @param workingFileCount 作業中ファイル件数
 */
public record CheckpointSavedPayload(String taskId, String reason, int workingFileCount)
    implements AgentEventPayload {}
