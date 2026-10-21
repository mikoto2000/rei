package dev.mikoto2000.rei.task;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * タスクの管理で受け渡すタスクを表す。
 *
 * @param id 識別子
 * @param title タイトル
 * @param dueDate 期限日付
 * @param priority 優先度
 * @param status 状態
 * @param createdAt 作成日時
 * @param completedAt 完了日時
 */
public record Task(
    long id,
    String title,
    LocalDate dueDate,
    int priority,
    TaskStatus status,
    OffsetDateTime createdAt,
    OffsetDateTime completedAt) {}
