package dev.mikoto2000.rei.feed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.mockito.Mockito;

import dev.mikoto2000.rei.websearch.WebSearchPage;

class FeedSummaryServiceTest {

  @TempDir
  Path tempDir;
  private int databaseSequence;

  @Test
  void summarizeRecentItemsBuildsPromptFromBriefingItems() {
    FeedService feedService = newService();
    Feed feed = feedService.add("https://example.com/feed.xml", "Example Feed");
    feedService.saveFetchedItems(feed.id(), List.of(
        new FetchedFeedItem("Today", "https://example.com/today", OffsetDateTime.of(2026, 4, 22, 7, 0, 0, 0, ZoneOffset.UTC))),
        OffsetDateTime.of(2026, 4, 22, 8, 0, 0, 0, ZoneOffset.UTC));
    AtomicReference<String> promptRef = new AtomicReference<>();
    FeedSummaryService service = new FeedSummaryService(
        feedService,
        item -> new WebSearchPage(
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

    String summary = service.summarizeBriefing(
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

  @Test
  void summarizeBriefingReturnsGeneratedSummaryAsIs() {
    FeedService feedService = newService();
    Feed feed = feedService.add("https://example.com/feed.xml", "Example Feed");
    feedService.saveFetchedItems(feed.id(), List.of(
        new FetchedFeedItem("Today", "https://example.com/today", OffsetDateTime.of(2026, 4, 22, 7, 0, 0, 0, ZoneOffset.UTC)),
        new FetchedFeedItem("Yesterday", "https://example.com/yesterday", OffsetDateTime.of(2026, 4, 21, 3, 0, 0, 0, ZoneOffset.UTC))),
        OffsetDateTime.of(2026, 4, 22, 8, 0, 0, 0, ZoneOffset.UTC));
    FeedSummaryService service = new FeedSummaryService(
        feedService,
        item -> new WebSearchPage(item.title(), item.url(), "", item.publishedAt().toString(), "Fetched article body"),
        prompt -> "briefing summary",
        new FeedProperties(20));

    String summary = service.summarizeBriefing(
        OffsetDateTime.of(2026, 4, 21, 0, 0, 0, 0, ZoneOffset.UTC),
        OffsetDateTime.of(2026, 4, 22, 9, 0, 0, 0, ZoneOffset.UTC));

    assertEquals("briefing summary", summary);
  }

  @Test
  void summarizeBriefingReturnsNoItemsMessageWhenEmpty() {
    FeedSummaryService service = new FeedSummaryService(
        newService(),
        item -> new WebSearchPage(item.title(), item.url(), "", item.publishedAt() == null ? null : item.publishedAt().toString(), ""),
        prompt -> "unused",
        new FeedProperties(20));

    String summary = service.summarizeBriefing(
        OffsetDateTime.of(2026, 4, 21, 0, 0, 0, 0, ZoneOffset.UTC),
        OffsetDateTime.of(2026, 4, 22, 9, 0, 0, 0, ZoneOffset.UTC));

    assertEquals("昨日 00:00 以降の新着記事はありませんでした", summary);
  }

  @Test
  void summarizeItemBuildsPromptFromSelectedItem() {
    FeedService feedService = newService();
    Feed feed = feedService.add("https://example.com/feed.xml", "Example Feed");
    feedService.saveFetchedItems(feed.id(), List.of(
        new FetchedFeedItem("Today", "https://example.com/today", OffsetDateTime.of(2026, 4, 22, 7, 0, 0, 0, ZoneOffset.UTC))),
        OffsetDateTime.of(2026, 4, 22, 8, 0, 0, 0, ZoneOffset.UTC));
    long itemId = feedService.listItemsForFeed(feed.id()).getFirst().id();
    AtomicReference<String> promptRef = new AtomicReference<>();
    FeedSummaryService service = new FeedSummaryService(
        feedService,
        item -> new WebSearchPage(
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

  @Test
  void summarizeItemFallsBackToMetadataWhenPageFetchFails() {
    FeedService feedService = newService();
    Feed feed = feedService.add("https://example.com/feed.xml", "Example Feed");
    feedService.saveFetchedItems(feed.id(), List.of(
        new FetchedFeedItem("Today", "https://example.com/today", OffsetDateTime.of(2026, 4, 22, 7, 0, 0, 0, ZoneOffset.UTC))),
        OffsetDateTime.of(2026, 4, 22, 8, 0, 0, 0, ZoneOffset.UTC));
    long itemId = feedService.listItemsForFeed(feed.id()).getFirst().id();
    AtomicReference<String> promptRef = new AtomicReference<>();
    FeedSummaryService service = new FeedSummaryService(
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

  @Test
  void summarizeItemDetailedReportsArticleSourceWhenFetchSucceeds() {
    FeedService feedService = serviceWithItem("https://example.com/article", "FEED_DESCRIPTION", "FEED_CONTENT");
    long itemId = feedService.listItemsForFeed(1L).getFirst().id();
    AtomicReference<String> promptRef = new AtomicReference<>();
    FeedSummaryService service = summaryService(feedService,
        item -> new WebSearchPage(item.title(), item.url(), "", null, "ARTICLE_BODY_MARKER"), promptRef);

    FeedItemSummaryResult result = service.summarizeItemDetailed(itemId);

    assertEquals("generated summary", result.summary());
    assertEquals("article", result.summarySource());
    assertEquals("success", result.articleFetchStatus());
    assertTrue(promptRef.get().contains("ARTICLE_BODY_MARKER"));
    org.junit.jupiter.api.Assertions.assertFalse(promptRef.get().contains("FEED_DESCRIPTION"));
  }

  @Test
  void summarizeItemDetailedFallsBackToFeedWhenFetchFailsOrIsEmpty() {
    for (boolean throwsFailure : List.of(true, false)) {
      FeedService feedService = serviceWithItem("https://example.com/article", "FEED_DESCRIPTION_MARKER", null);
      long itemId = feedService.listItemsForFeed(1L).getFirst().id();
      AtomicReference<String> promptRef = new AtomicReference<>();
      FeedSummaryService service = summaryService(feedService, item -> {
        if (throwsFailure) throw new IllegalStateException("fetch failed");
        return new WebSearchPage(item.title(), item.url(), "", null, "  ");
      }, promptRef);

      FeedItemSummaryResult result = service.summarizeItemDetailed(itemId);

      assertEquals("feed", result.summarySource());
      assertEquals("failed", result.articleFetchStatus());
      assertTrue(promptRef.get().contains("FEED_DESCRIPTION_MARKER"));
      assertTrue(promptRef.get().contains("記事本文は取得できませんでした"));
    }
  }

  @Test
  void summarizeItemDetailedUsesFeedWithoutFetchWhenUrlIsMissing() {
    FeedService feedService = serviceWithItem(null, null, "EMBEDDED_CONTENT_MARKER");
    long itemId = feedService.listItemsForFeed(1L).getFirst().id();
    AtomicReference<String> promptRef = new AtomicReference<>();
    java.util.concurrent.atomic.AtomicInteger fetches = new java.util.concurrent.atomic.AtomicInteger();
    FeedSummaryService service = summaryService(feedService, item -> {
      fetches.incrementAndGet();
      throw new AssertionError("must not fetch");
    }, promptRef);

    FeedItemSummaryResult result = service.summarizeItemDetailed(itemId);

    assertEquals("feed", result.summarySource());
    assertEquals("not_requested", result.articleFetchStatus());
    assertEquals(0, fetches.get());
    assertTrue(promptRef.get().contains("EMBEDDED_CONTENT_MARKER"));
  }

  @Test
  void briefingUsesArticleAndFeedFallbackAndDeduplicatesUrls() {
    FeedService feedService = Mockito.mock(FeedService.class);
    OffsetDateTime from = OffsetDateTime.parse("2026-04-21T00:00:00Z");
    OffsetDateTime to = OffsetDateTime.parse("2026-04-22T09:00:00Z");
    List<FeedBriefingItem> items = List.of(
        new FeedBriefingItem(1, "A", "https://example.com/shared", null, null,
            OffsetDateTime.parse("2026-04-22T08:00:00Z"), "Feed A"),
        new FeedBriefingItem(2, "B", "https://example.com/failure", "FEED_B_FALLBACK", null,
            OffsetDateTime.parse("2026-04-22T07:00:00Z"), "Feed B"),
        new FeedBriefingItem(3, "C", "https://example.com/shared", null, null,
            OffsetDateTime.parse("2026-04-22T06:00:00Z"), "Feed C"));
    Mockito.when(feedService.listBriefingItems(from, to, 20)).thenReturn(items);
    java.util.Map<String, Integer> fetchCounts = new java.util.HashMap<>();
    AtomicReference<String> promptRef = new AtomicReference<>();
    FeedSummaryService service = new FeedSummaryService(feedService, item -> {
      fetchCounts.merge(item.url(), 1, Integer::sum);
      if (item.url().endsWith("failure")) throw new IllegalStateException("timeout");
      return new WebSearchPage(item.title(), item.url(), "", null, "SHARED_ARTICLE_BODY");
    }, prompt -> {
      promptRef.set(prompt);
      return "briefing summary";
    }, new FeedProperties(20));

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

  private FeedService serviceWithItem(String url, String description, String content) {
    FeedService service = newService();
    Feed feed = service.add("https://example.com/feed.xml", "Example Feed");
    service.saveFetchedItems(feed.id(), List.of(new FetchedFeedItem("Today", url,
        OffsetDateTime.of(2026, 4, 22, 7, 0, 0, 0, ZoneOffset.UTC), description, content)),
        OffsetDateTime.of(2026, 4, 22, 8, 0, 0, 0, ZoneOffset.UTC));
    return service;
  }

  private FeedSummaryService summaryService(FeedService feedService,
      java.util.function.Function<FeedBriefingItem, WebSearchPage> fetcher, AtomicReference<String> promptRef) {
    return new FeedSummaryService(feedService, fetcher, prompt -> {
      promptRef.set(prompt);
      return "generated summary";
    }, new FeedProperties(20));
  }

  private FeedService newService() {
    return new FeedService(new DriverManagerDataSource(
        "jdbc:sqlite:" + tempDir.resolve("feed-summary-" + databaseSequence++ + ".db")));
  }
}
