package dev.mikoto2000.rei.web;
import java.util.List;
public record BriefingResponse(String date, String overview, List<EventResponse> events, List<TaskResponse> openTasks,
    List<TaskResponse> overdueTasks, List<String> relatedDocuments, List<ArticleResponse> feedItems,
    List<String> interestUpdates, List<String> cautionPoints, List<String> nextActions) {
  public record EventResponse(String id, String summary, String start, String end, String location, String status) {}
  public record TaskResponse(long id, String title, String dueDate, int priority, String status) {
    static TaskResponse from(dev.mikoto2000.rei.task.Task t) { return new TaskResponse(t.id(), t.title(), t.dueDate() == null ? null : t.dueDate().toString(), t.priority(), t.status().name()); }
  }
  public record ArticleResponse(long id, String title, String url, String publishedAt, String feedName) {}
  public static BriefingResponse from(dev.mikoto2000.rei.briefing.DailyBriefing b) {
    return new BriefingResponse(b.date().toString(), b.overview(), b.events().stream().map(e -> new EventResponse(e.id(), e.summary(), e.start(), e.end(), e.location(), e.status())).toList(),
        b.openTasks().stream().map(TaskResponse::from).toList(), b.overdueTasks().stream().map(TaskResponse::from).toList(), b.relatedDocuments(),
        b.feedItems().stream().map(f -> new ArticleResponse(f.id(), f.title(), f.url(), f.publishedAt() == null ? null : f.publishedAt().toString(), f.feedName())).toList(), b.interestUpdates(), b.cautionPoints(), b.nextActions());
  }
}
