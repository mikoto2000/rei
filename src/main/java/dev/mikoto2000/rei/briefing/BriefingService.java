package dev.mikoto2000.rei.briefing;

import dev.mikoto2000.rei.feed.FeedBriefingItem;
import dev.mikoto2000.rei.feed.FeedProperties;
import dev.mikoto2000.rei.feed.FeedService;
import dev.mikoto2000.rei.googlecalendar.GoogleCalendarEventSummary;
import dev.mikoto2000.rei.googlecalendar.GoogleCalendarService;
import dev.mikoto2000.rei.interest.InterestUpdateService;
import dev.mikoto2000.rei.task.Task;
import dev.mikoto2000.rei.task.TaskService;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

/** 日次案内のユースケースを実現する。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BriefingService {

  /** Google予定表サービス。 */
  private final GoogleCalendarService googleCalendarService;

  /** タスクサービス。 */
  private final TaskService taskService;

  /** ベクトル保存先。 */
  private final VectorStore vectorStore;

  /** 日次案内読み上げの案内。 */
  private final BriefingNarrator briefingNarrator;

  /** 関心更新サービス。 */
  private final InterestUpdateService interestUpdateService;

  /** フィードサービス。 */
  private final FeedService feedService;

  /** フィード設定。 */
  private final FeedProperties feedProperties;

  /** 日次情報の案内で用いる今日を返す。 */
  public DailyBriefing today() throws Exception {
    return briefingFor(LocalDate.now(googleCalendarService.zoneId()));
  }

  /** 日次情報の案内で用いる日次案内対象を返す。 */
  public DailyBriefing briefingFor(LocalDate date) throws Exception {
    List<GoogleCalendarEventSummary> events = googleCalendarService.listEventsForDate(date);
    List<Task> openTasks = taskService.listOpen();
    List<Task> overdueTasks =
        openTasks.stream()
            .filter(task -> task.dueDate() != null && task.dueDate().isBefore(date))
            .toList();
    List<String> relatedDocuments = findRelatedDocuments(events, openTasks);
    List<FeedBriefingItem> feedItems =
        feedService.listBriefingItems(
            date.minusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC),
            date.plusDays(1).atStartOfDay().minusSeconds(1).atOffset(ZoneOffset.UTC),
            feedProperties.briefingMaxItems());
    List<String> interestUpdates =
        interestUpdateService.listRecent(24).stream()
            .map(
                update -> {
                  String url =
                      update.sourceUrls().isEmpty() ? "" : " | " + update.sourceUrls().getFirst();
                  return update.topic() + " | " + update.summary() + url;
                })
            .toList();

    if (events.isEmpty() && openTasks.isEmpty()) {
      return fallbackBriefing(date, relatedDocuments, feedItems, interestUpdates);
    }

    BriefingContext context =
        new BriefingContext(date, events, openTasks, overdueTasks, relatedDocuments);
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

  /** 日次情報の案内で用いる代替手段日次案内を返す。 */
  private DailyBriefing fallbackBriefing(
      LocalDate date,
      List<String> relatedDocuments,
      List<FeedBriefingItem> feedItems,
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

  /** 対象を特定できるよう、関連保存済み文書を探す。 */
  private List<String> findRelatedDocuments(
      List<GoogleCalendarEventSummary> events, List<Task> openTasks) {
    String query = buildDocumentQuery(events, openTasks);
    if (query.isBlank()) {
      return List.of();
    }

    List<Document> documents;
    try {
      documents =
          vectorStore.similaritySearch(
              SearchRequest.builder().query(query).topK(3).similarityThresholdAll().build());
    } catch (RuntimeException exception) {
      log.warn(
          "関連文書の検索に失敗したため、関連文書なしでブリーフィングを生成します。"
              + "埋め込み API の REI_OPENAI_EMBEDDING_BASE_URL / REI_OPENAI_EMBEDDING_PATH / "
              + "REI_OPENAI_EMBEDDING_MODEL を確認してください。",
          exception);
      return List.of();
    }
    return documents.stream()
        .map(this::formatDocument)
        .filter(value -> !value.isBlank())
        .collect(Collectors.toCollection(LinkedHashSet::new))
        .stream()
        .toList();
  }

  /** 利用先に渡せるよう、文書検索条件を組み立てる。 */
  private String buildDocumentQuery(List<GoogleCalendarEventSummary> events, List<Task> openTasks) {
    return java.util.stream.Stream.concat(
            events.stream().map(GoogleCalendarEventSummary::summary),
            openTasks.stream().map(Task::title))
        .filter(value -> value != null && !value.isBlank())
        .collect(Collectors.joining(" "));
  }

  /** 内容を表示・参照しやすくするため、文書を整形する。 */
  private String formatDocument(Document document) {
    Object source = document.getMetadata().get("source");
    String sourceLabel = source == null ? "" : source.toString();
    String text =
        document.getText() == null ? "" : document.getText().replaceAll("\\s+", " ").trim();
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
