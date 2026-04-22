package dev.mikoto2000.rei.briefing;

import java.time.LocalDate;
import java.util.List;

import dev.mikoto2000.rei.feed.FeedBriefingItem;
import dev.mikoto2000.rei.googlecalendar.GoogleCalendarEventSummary;
import dev.mikoto2000.rei.task.Task;

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
    List<String> nextActions
) {}
