package dev.mikoto2000.rei.briefing;

import dev.mikoto2000.rei.feed.FeedBriefingItem;
import dev.mikoto2000.rei.googlecalendar.GoogleCalendarEventSummary;
import dev.mikoto2000.rei.task.Task;
import java.time.LocalDate;
import java.util.List;

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
