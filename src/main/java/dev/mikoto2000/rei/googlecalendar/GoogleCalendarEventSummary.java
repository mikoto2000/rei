package dev.mikoto2000.rei.googlecalendar;

public record GoogleCalendarEventSummary(
    String id,
    String summary,
    String start,
    String end,
    String location,
    String status
) {}
