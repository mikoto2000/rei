package dev.mikoto2000.rei.task;

import java.time.LocalDate;

/**
 * タスクの管理で受け渡すタスク検索条件を表す。
 *
 * @param priority 優先度
 * @param dueBefore 期限変更前
 */
public record TaskQuery(Integer priority, LocalDate dueBefore) {}
