package dev.mikoto2000.rei.briefing;

import dev.mikoto2000.rei.googlecalendar.GoogleCalendarEventSummary;
import dev.mikoto2000.rei.task.Task;
import java.time.LocalDate;
import java.util.List;

/**
 * 日次情報の案内で受け渡す日次案内文脈を表す。
 *
 * @param date 日付
 * @param events イベント一覧
 * @param openTasks 開始タスク一覧
 * @param overdueTasks overdueタスク一覧
 * @param relatedDocuments 関連保存済み文書
 */
public record BriefingContext(
    LocalDate date,
    List<GoogleCalendarEventSummary> events,
    List<Task> openTasks,
    List<Task> overdueTasks,
    List<String> relatedDocuments) {}
