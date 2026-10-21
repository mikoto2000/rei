package dev.mikoto2000.rei.briefing;

import dev.mikoto2000.rei.feed.FeedBriefingItem;
import dev.mikoto2000.rei.googlecalendar.GoogleCalendarEventSummary;
import dev.mikoto2000.rei.task.Task;
import java.time.LocalDate;
import java.util.List;

/**
 * 日次情報の案内で受け渡す日次日次案内を表す。
 *
 * @param date 日付
 * @param events イベント一覧
 * @param openTasks 開始タスク一覧
 * @param overdueTasks overdueタスク一覧
 * @param relatedDocuments 関連保存済み文書
 * @param feedItems フィード項目一覧
 * @param interestUpdates 関心Updates
 * @param overview 概要
 * @param cautionPoints 確認時に注意する点
 * @param nextActions 次操作一覧
 */
public record DailyBriefing(
    LocalDate date,
    List<GoogleCalendarEventSummary> events,
    List<Task> openTasks,
    List<Task> overdueTasks,
    List<String> relatedDocuments,
    List<FeedBriefingItem> feedItems,
    List<String> interestUpdates,
    String overview,
    List<String> cautionPoints,
    List<String> nextActions) {}
