package dev.mikoto2000.rei.temporal;

import java.time.Instant;

/**
 * 時刻と期間の解釈で受け渡す実行予約エージェントタスクを表す。
 *
 * @param id 識別子
 * @param createdAt 作成日時
 * @param executeAt 実行日時
 * @param action 操作
 * @param conversationId 会話の識別子
 */
public record ScheduledAgentTask(
    String id, Instant createdAt, Instant executeAt, String action, String conversationId) {}
