package dev.mikoto2000.rei.briefing;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import dev.mikoto2000.rei.feed.FeedBriefingItem;
import dev.mikoto2000.rei.feed.FeedProperties;
import dev.mikoto2000.rei.feed.FeedService;
import dev.mikoto2000.rei.googlecalendar.GoogleCalendarEventSummary;
import dev.mikoto2000.rei.googlecalendar.GoogleCalendarService;
import dev.mikoto2000.rei.interest.InterestUpdateService;
import dev.mikoto2000.rei.task.Task;
import dev.mikoto2000.rei.task.TaskService;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class BriefingService {

  private final GoogleCalendarService googleCalendarService;
  private final TaskService taskService;
  private final VectorStore vectorStore;
  private final BriefingNarrator briefingNarrator;
  private final InterestUpdateService interestUpdateService;
  private final FeedService feedService;
  private final FeedProperties feedProperties;

  public DailyBriefing today() throws Exception {
    return briefingFor(LocalDate.now(googleCalendarService.zoneId()));
  }

  public DailyBriefing briefingFor(LocalDate date) throws Exception {
    List<GoogleCalendarEventSummary> events = googleCalendarService.listEventsForDate(date);
    List<Task> openTasks = taskService.listOpen();
    List<Task> overdueTasks = openTasks.stream()
        .filter(task -> task.dueDate() != null && task.dueDate().isBefore(date))
        .toList();
    List<String> relatedDocuments = findRelatedDocuments(events, openTasks);
    List<FeedBriefingItem> feedItems = feedService.listBriefingItems(
        date.minusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC),
        date.plusDays(1).atStartOfDay().minusSeconds(1).atOffset(ZoneOffset.UTC),
        feedProperties.briefingMaxItems());
    List<String> interestUpdates = interestUpdateService.listRecent(24).stream()
        .map(update -> {
          String url = update.sourceUrls().isEmpty() ? "" : " | " + update.sourceUrls().getFirst();
          return update.topic() + " | " + update.summary() + url;
        })
        .toList();

    if (events.isEmpty() && openTasks.isEmpty()) {
      return fallbackBriefing(date, relatedDocuments, feedItems, interestUpdates);
    }

    BriefingContext context = new BriefingContext(date, events, openTasks, overdueTasks, relatedDocuments);
    BriefingNarration narration = briefingNarrator.narrate(context);
    return new DailyBriefing(
        date,
        events,
        openTasks,
        overdueTasks,
        relatedDocuments,
        feedItems,
        interestUpdates,
        narration.overview(),
        narration.cautionPoints(),
        narration.nextActions());
  }

  private DailyBriefing fallbackBriefing(LocalDate date, List<String> relatedDocuments, List<FeedBriefingItem> feedItems,
      List<String> interestUpdates) {
    return new DailyBriefing(
        date,
        List.of(),
        List.of(),
        List.of(),
        relatedDocuments,
        feedItems,
        interestUpdates,
        "今日は予定も未完了タスクもありません。必要なら先回りで準備や整理を進められます。",
        List.of("急ぎの対応は見当たりません。"),
        List.of("新しく着手するタスクや確認事項を整理する。"));
  }

  private List<String> findRelatedDocuments(List<GoogleCalendarEventSummary> events, List<Task> openTasks) {
    String query = buildDocumentQuery(events, openTasks);
    if (query.isBlank()) {
      return List.of();
    }

    return vectorStore.similaritySearch(SearchRequest.builder()
            .query(query)
            .topK(3)
            .similarityThresholdAll()
            .build())
        .stream()
        .map(this::formatDocument)
        .filter(value -> !value.isBlank())
        .collect(Collectors.toCollection(LinkedHashSet::new))
        .stream()
        .toList();
  }

  private String buildDocumentQuery(List<GoogleCalendarEventSummary> events, List<Task> openTasks) {
    return java.util.stream.Stream.concat(
            events.stream().map(GoogleCalendarEventSummary::summary),
            openTasks.stream().map(Task::title))
        .filter(value -> value != null && !value.isBlank())
        .collect(Collectors.joining(" "));
  }

  private String formatDocument(Document document) {
    Object source = document.getMetadata().get("source");
    String sourceLabel = source == null ? "" : source.toString();
    String text = document.getText() == null ? "" : document.getText().replaceAll("\\s+", " ").trim();
    String snippet = text.length() > 80 ? text.substring(0, 80) + "..." : text;

    if (!sourceLabel.isBlank() && !snippet.isBlank()) {
      return sourceLabel + " | " + snippet;
    }
    if (!sourceLabel.isBlank()) {
      return sourceLabel;
    }
    return snippet;
  }
}
