package dev.mikoto2000.rei.googlecalendar;

/**
 * 予定の管理で受け渡すGoogle予定表イベント要約を表す。
 *
 * @param id 識別子
 * @param summary 要約
 * @param start 開始
 * @param end 終了
 * @param location 場所
 * @param status 状態
 */
public record GoogleCalendarEventSummary(
    String id, String summary, String start, String end, String location, String status) {}
