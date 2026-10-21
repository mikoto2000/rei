package dev.mikoto2000.rei.feed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.websearch.WebSearchPage;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** フィード要約サービスの結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class FeedSummaryServiceTest {

  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path tempDir;

  /** 検証に使うデータベース発生順序。 */
  private int databaseSequence;

  /** フィードの購読と収集で、記録した情報を、定められた分類と集約の規則に従ってまとめることを確認する。 */
  @Test
  void summarizeRecentItemsBuildsPromptFromBriefingItems() {
    FeedService feedService = newService();
    Feed feed = feedService.add("https://example.com/feed.xml", "Example Feed");
    feedService.saveFetchedItems(
        feed.id(),
        List.of(
            new FetchedFeedItem(
                "Today",
                "https://example.com/today",
                OffsetDateTime.of(2026, 4, 22, 7, 0, 0, 0, ZoneOffset.UTC))),
        OffsetDateTime.of(2026, 4, 22, 8, 0, 0, 0, ZoneOffset.UTC));
    AtomicReference<String> promptRef = new AtomicReference<>();
    FeedSummaryService service =
        new FeedSummaryService(
            feedService,
            item ->
                new WebSearchPage(
                    item.title(),
                    item.url(),
                    "",
                    item.publishedAt().toString(),
                    "Fetched article body"),
            prompt -> {
              promptRef.set(prompt);
              return "briefing summary";
            },
            new FeedProperties(20));

    String summary =
        service.summarizeBriefing(
            OffsetDateTime.of(2026, 4, 21, 0, 0, 0, 0, ZoneOffset.UTC),
            OffsetDateTime.of(2026, 4, 22, 9, 0, 0, 0, ZoneOffset.UTC));

    assertEquals("briefing summary", summary);
    assertTrue(promptRef.get().contains("Today"));
    assertTrue(promptRef.get().contains("Example Feed"));
    assertTrue(promptRef.get().contains("Fetched article body"));
    assertTrue(promptRef.get().contains("重要そうな記事"));
    assertTrue(promptRef.get().contains("後で読む価値が高いもの"));
    assertTrue(promptRef.get().contains("紹介文の直後に対応する URL"));
  }

  /** 要約日次案内について、生成済み要約形式を返すことを確認する。 */
  @Test
  void summarizeBriefingReturnsGeneratedSummaryAsIs() {
    FeedService feedService = newService();
    Feed feed = feedService.add("https://example.com/feed.xml", "Example Feed");
    feedService.saveFetchedItems(
        feed.id(),
        List.of(
            new FetchedFeedItem(
                "Today",
                "https://example.com/today",
                OffsetDateTime.of(2026, 4, 22, 7, 0, 0, 0, ZoneOffset.UTC)),
            new FetchedFeedItem(
                "Yesterday",
                "https://example.com/yesterday",
                OffsetDateTime.of(2026, 4, 21, 3, 0, 0, 0, ZoneOffset.UTC))),
        OffsetDateTime.of(2026, 4, 22, 8, 0, 0, 0, ZoneOffset.UTC));
    FeedSummaryService service =
        new FeedSummaryService(
            feedService,
            item ->
                new WebSearchPage(
                    item.title(),
                    item.url(),
                    "",
                    item.publishedAt().toString(),
                    "Fetched article body"),
            prompt -> "briefing summary",
            new FeedProperties(20));

    String summary =
        service.summarizeBriefing(
            OffsetDateTime.of(2026, 4, 21, 0, 0, 0, 0, ZoneOffset.UTC),
            OffsetDateTime.of(2026, 4, 22, 9, 0, 0, 0, ZoneOffset.UTC));

    assertEquals("briefing summary", summary);
  }

  /** 要約日次案内について、なし項目一覧メッセージを返すことを確認する。 */
  @Test
  void summarizeBriefingReturnsNoItemsMessageWhenEmpty() {
    FeedSummaryService service =
        new FeedSummaryService(
            newService(),
            item ->
                new WebSearchPage(
                    item.title(),
                    item.url(),
                    "",
                    item.publishedAt() == null ? null : item.publishedAt().toString(),
                    ""),
            prompt -> "unused",
            new FeedProperties(20));

    String summary =
        service.summarizeBriefing(
            OffsetDateTime.of(2026, 4, 21, 0, 0, 0, 0, ZoneOffset.UTC),
            OffsetDateTime.of(2026, 4, 22, 9, 0, 0, 0, ZoneOffset.UTC));

    assertEquals("昨日 00:00 以降の新着記事はありませんでした", summary);
  }

  /** フィードの購読と収集で、記録した情報を、定められた分類と集約の規則に従ってまとめることを確認する。 */
  @Test
  void summarizeItemBuildsPromptFromSelectedItem() {
    FeedService feedService = newService();
    Feed feed = feedService.add("https://example.com/feed.xml", "Example Feed");
    feedService.saveFetchedItems(
        feed.id(),
        List.of(
            new FetchedFeedItem(
                "Today",
                "https://example.com/today",
                OffsetDateTime.of(2026, 4, 22, 7, 0, 0, 0, ZoneOffset.UTC))),
        OffsetDateTime.of(2026, 4, 22, 8, 0, 0, 0, ZoneOffset.UTC));
    long itemId = feedService.listItemsForFeed(feed.id()).getFirst().id();
    AtomicReference<String> promptRef = new AtomicReference<>();
    FeedSummaryService service =
        new FeedSummaryService(
            feedService,
            item ->
                new WebSearchPage(
                    item.title(),
                    item.url(),
                    "",
                    item.publishedAt().toString(),
                    "Fetched item body"),
            prompt -> {
              promptRef.set(prompt);
              return "item summary";
            },
            new FeedProperties(20));

    String summary = service.summarizeItem(itemId);

    assertEquals("item summary", summary);
    assertTrue(promptRef.get().contains("Today"));
    assertTrue(promptRef.get().contains("https://example.com/today"));
    assertTrue(promptRef.get().contains("Fetched item body"));
  }

  /** フィードの購読と収集で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void summarizeItemFallsBackToMetadataWhenPageFetchFails() {
    FeedService feedService = newService();
    Feed feed = feedService.add("https://example.com/feed.xml", "Example Feed");
    feedService.saveFetchedItems(
        feed.id(),
        List.of(
            new FetchedFeedItem(
                "Today",
                "https://example.com/today",
                OffsetDateTime.of(2026, 4, 22, 7, 0, 0, 0, ZoneOffset.UTC))),
        OffsetDateTime.of(2026, 4, 22, 8, 0, 0, 0, ZoneOffset.UTC));
    long itemId = feedService.listItemsForFeed(feed.id()).getFirst().id();
    AtomicReference<String> promptRef = new AtomicReference<>();
    FeedSummaryService service =
        new FeedSummaryService(
            feedService,
            item -> {
              throw new IllegalStateException("fetch failed");
            },
            prompt -> {
              promptRef.set(prompt);
              return "item summary";
            },
            new FeedProperties(20));

    String summary = service.summarizeItem(itemId);

    assertEquals("item summary", summary);
    assertTrue(promptRef.get().contains("Today"));
    assertTrue(promptRef.get().contains("https://example.com/today"));
  }

  /** 記事取得元を伝えることを確認する。 */
  @Test
  void summarizeItemDetailedReportsArticleSourceWhenFetchSucceeds() {
    FeedService feedService =
        serviceWithItem("https://example.com/article", "FEED_DESCRIPTION", "FEED_CONTENT");
    long itemId = feedService.listItemsForFeed(1L).getFirst().id();
    AtomicReference<String> promptRef = new AtomicReference<>();
    FeedSummaryService service =
        summaryService(
            feedService,
            item -> new WebSearchPage(item.title(), item.url(), "", null, "ARTICLE_BODY_MARKER"),
            promptRef);

    FeedItemSummaryResult result = service.summarizeItemDetailed(itemId);

    assertEquals("generated summary", result.summary());
    assertEquals("article", result.summarySource());
    assertEquals("success", result.articleFetchStatus());
    assertTrue(promptRef.get().contains("ARTICLE_BODY_MARKER"));
    org.junit.jupiter.api.Assertions.assertFalse(promptRef.get().contains("FEED_DESCRIPTION"));
  }

  /** フィードの購読と収集で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void summarizeItemDetailedFallsBackToFeedWhenFetchFailsOrIsEmpty() {
    for (boolean throwsFailure : List.of(true, false)) {
      FeedService feedService =
          serviceWithItem("https://example.com/article", "FEED_DESCRIPTION_MARKER", null);
      long itemId = feedService.listItemsForFeed(1L).getFirst().id();
      AtomicReference<String> promptRef = new AtomicReference<>();
      FeedSummaryService service =
          summaryService(
              feedService,
              item -> {
                if (throwsFailure) throw new IllegalStateException("fetch failed");
                return new WebSearchPage(item.title(), item.url(), "", null, "  ");
              },
              promptRef);

      FeedItemSummaryResult result = service.summarizeItemDetailed(itemId);

      assertEquals("feed", result.summarySource());
      assertEquals("failed", result.articleFetchStatus());
      assertTrue(promptRef.get().contains("FEED_DESCRIPTION_MARKER"));
      assertTrue(promptRef.get().contains("記事本文は取得できませんでした"));
    }
  }

  /** フィードを利用することを確認する。 */
  @Test
  void summarizeItemDetailedUsesFeedWithoutFetchWhenUrlIsMissing() {
    FeedService feedService = serviceWithItem(null, null, "EMBEDDED_CONTENT_MARKER");
    long itemId = feedService.listItemsForFeed(1L).getFirst().id();
    AtomicReference<String> promptRef = new AtomicReference<>();
    java.util.concurrent.atomic.AtomicInteger fetches =
        new java.util.concurrent.atomic.AtomicInteger();
    FeedSummaryService service =
        summaryService(
            feedService,
            item -> {
              fetches.incrementAndGet();
              throw new AssertionError("must not fetch");
            },
            promptRef);

    FeedItemSummaryResult result = service.summarizeItemDetailed(itemId);

    assertEquals("feed", result.summarySource());
    assertEquals("not_requested", result.articleFetchStatus());
    assertEquals(0, fetches.get());
    assertTrue(promptRef.get().contains("EMBEDDED_CONTENT_MARKER"));
  }

  /** 日次案内について、記事を利用することを確認する。 */
  @Test
  void briefingUsesArticleAndFeedFallbackAndDeduplicatesUrls() {
    FeedService feedService = Mockito.mock(FeedService.class);
    OffsetDateTime from = OffsetDateTime.parse("2026-04-21T00:00:00Z");
    OffsetDateTime to = OffsetDateTime.parse("2026-04-22T09:00:00Z");
    List<FeedBriefingItem> items =
        List.of(
            new FeedBriefingItem(
                1,
                "A",
                "https://example.com/shared",
                null,
                null,
                OffsetDateTime.parse("2026-04-22T08:00:00Z"),
                "Feed A"),
            new FeedBriefingItem(
                2,
                "B",
                "https://example.com/failure",
                "FEED_B_FALLBACK",
                null,
                OffsetDateTime.parse("2026-04-22T07:00:00Z"),
                "Feed B"),
            new FeedBriefingItem(
                3,
                "C",
                "https://example.com/shared",
                null,
                null,
                OffsetDateTime.parse("2026-04-22T06:00:00Z"),
                "Feed C"));
    Mockito.when(feedService.listBriefingItems(from, to, 20)).thenReturn(items);
    java.util.Map<String, Integer> fetchCounts = new java.util.HashMap<>();
    AtomicReference<String> promptRef = new AtomicReference<>();
    FeedSummaryService service =
        new FeedSummaryService(
            feedService,
            item -> {
              fetchCounts.merge(item.url(), 1, Integer::sum);
              if (item.url().endsWith("failure")) throw new IllegalStateException("timeout");
              return new WebSearchPage(item.title(), item.url(), "", null, "SHARED_ARTICLE_BODY");
            },
            prompt -> {
              promptRef.set(prompt);
              return "briefing summary";
            },
            new FeedProperties(20));

    String result = service.summarizeBriefing(from, to);

    assertEquals("briefing summary", result);
    assertEquals(1, fetchCounts.get("https://example.com/shared"));
    assertEquals(1, fetchCounts.get("https://example.com/failure"));
    assertTrue(promptRef.get().contains("SHARED_ARTICLE_BODY"));
    assertTrue(promptRef.get().contains("FEED_B_FALLBACK"));
    assertTrue(promptRef.get().contains("summarySource=article"));
    assertTrue(promptRef.get().contains("summarySource=feed"));
    assertTrue(promptRef.get().contains("articleFetchStatus=failed"));
  }

  /** フィードの購読と収集で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void summarizeItemDoesNotInvokeLlmWhenArticleAndFeedInformationAreEmpty() {
    FeedService feedService = newService();
    Feed feed = feedService.add("https://example.com/feed.xml", "Example Feed");
    feedService.saveFetchedItems(
        feed.id(),
        List.of(
            new FetchedFeedItem(
                " ",
                "https://example.com/empty",
                OffsetDateTime.parse("2026-04-22T07:00:00Z"),
                " ",
                " ")),
        OffsetDateTime.parse("2026-04-22T08:00:00Z"));
    long itemId = feedService.listItemsForFeed(1L).getFirst().id();
    java.util.concurrent.atomic.AtomicInteger generations =
        new java.util.concurrent.atomic.AtomicInteger();
    FeedSummaryService service =
        new FeedSummaryService(
            feedService,
            item -> new WebSearchPage("", item.url(), "", null, " "),
            prompt -> {
              generations.incrementAndGet();
              return "must not run";
            },
            new FeedProperties(20));

    FeedItemSummaryResult result = service.summarizeItemDetailed(itemId);

    assertEquals("要約可能な記事情報がありません。", result.summary());
    assertEquals("feed", result.summarySource());
    assertEquals("failed", result.articleFetchStatus());
    assertEquals(0, generations.get());
  }

  /** {@code serviceWithItem} の条件を再現するための補助情報を返す。 */
  private FeedService serviceWithItem(String url, String description, String content) {
    FeedService service = newService();
    Feed feed = service.add("https://example.com/feed.xml", "Example Feed");
    service.saveFetchedItems(
        feed.id(),
        List.of(
            new FetchedFeedItem(
                "Today",
                url,
                OffsetDateTime.of(2026, 4, 22, 7, 0, 0, 0, ZoneOffset.UTC),
                description,
                content)),
        OffsetDateTime.of(2026, 4, 22, 8, 0, 0, 0, ZoneOffset.UTC));
    return service;
  }

  /** {@code summaryService} の条件を再現するための補助情報を返す。 */
  private FeedSummaryService summaryService(
      FeedService feedService,
      java.util.function.Function<FeedBriefingItem, WebSearchPage> fetcher,
      AtomicReference<String> promptRef) {
    return new FeedSummaryService(
        feedService,
        fetcher,
        prompt -> {
          promptRef.set(prompt);
          return "generated summary";
        },
        new FeedProperties(20));
  }

  /** 指定された条件を再現するための検証対象や入力情報を作成する。 */
  private FeedService newService() {
    return new FeedService(
        new DriverManagerDataSource(
            "jdbc:sqlite:" + tempDir.resolve("feed-summary-" + databaseSequence++ + ".db")));
  }
}
