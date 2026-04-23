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

import dev.mikoto2000.rei.websearch.WebSearchPage;

class FeedSummaryServiceTest {

  @TempDir
  Path tempDir;

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

    assertTrue(summary.startsWith("briefing summary"));
    assertTrue(summary.contains("参考URL:"));
    assertTrue(summary.contains("https://example.com/today"));
    assertTrue(promptRef.get().contains("Today"));
    assertTrue(promptRef.get().contains("Example Feed"));
    assertTrue(promptRef.get().contains("Fetched article body"));
    assertTrue(promptRef.get().contains("重要そうな記事"));
    assertTrue(promptRef.get().contains("後で読む価値が高いもの"));
    assertTrue(promptRef.get().contains("紹介文の直後に対応する URL"));
  }

  @Test
  void summarizeBriefingAppendsSourceUrlsToGeneratedSummary() {
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

    assertTrue(summary.contains("briefing summary"));
    assertTrue(summary.contains("参考URL:"));
    assertTrue(summary.contains("https://example.com/today"));
    assertTrue(summary.contains("https://example.com/yesterday"));
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

  private FeedService newService() {
    return new FeedService(new DriverManagerDataSource("jdbc:sqlite:" + tempDir.resolve("feed-summary.db")));
  }
}
