package dev.mikoto2000.rei.feed;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class FeedUpdateServiceTest {

  @TempDir
  Path tempDir;

  @Test
  void updateStoresFeedMetadataAndItems() {
    FeedService feedService = newService();
    Feed feed = feedService.add("https://example.com/feed.xml", "Example Feed");
    FeedFetcher feedFetcher = new FeedFetcher(uri -> new FeedHttpResponse(200, """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0">
          <channel>
            <title>Fetched Title</title>
            <link>https://example.com/</link>
            <description>Fetched Description</description>
            <item>
              <title>First Post</title>
              <link>https://example.com/posts/1</link>
              <pubDate>Tue, 21 Apr 2026 09:30:00 GMT</pubDate>
            </item>
          </channel>
        </rss>
        """));
    FeedUpdateService updateService = new FeedUpdateService(feedService, feedFetcher);

    FeedUpdateResult result = updateService.update(feed.id());

    assertEquals(1, result.addedItems());
    Feed updated = feedService.list().getFirst();
    assertEquals("Fetched Title", updated.title());
    assertEquals("https://example.com/", updated.siteUrl());
    assertEquals("Fetched Description", updated.description());
    assertEquals(1, feedService.listItemsForFeed(feed.id()).size());
    assertEquals("First Post", feedService.listItemsForFeed(feed.id()).getFirst().title());
  }

  @Test
  void updateDeduplicatesItemsByUrl() {
    FeedService feedService = newService();
    Feed feed = feedService.add("https://example.com/feed.xml", "Example Feed");
    FeedFetcher feedFetcher = new FeedFetcher(uri -> new FeedHttpResponse(200, """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0">
          <channel>
            <title>Example RSS</title>
            <item>
              <title>First Post</title>
              <link>https://example.com/posts/1</link>
              <pubDate>Tue, 21 Apr 2026 09:30:00 GMT</pubDate>
            </item>
          </channel>
        </rss>
        """));
    FeedUpdateService updateService = new FeedUpdateService(feedService, feedFetcher);

    updateService.update(feed.id());
    FeedUpdateResult second = updateService.update(feed.id());

    assertEquals(0, second.addedItems());
    assertEquals(1, feedService.listItemsForFeed(feed.id()).size());
  }

  @Test
  void updateAllSkipsDisabledFeedsAndRecordsFailures() {
    FeedService feedService = newService();
    Feed okFeed = feedService.add("https://example.com/feed.xml", "Ok Feed");
    Feed disabledFeed = feedService.add("https://example.com/disabled.xml", "Disabled Feed");
    feedService.update(disabledFeed.id(), "Disabled Feed", false);
    Feed failingFeed = feedService.add("https://example.com/fail.xml", "Fail Feed");

    FeedFetcher feedFetcher = new FeedFetcher(uri -> {
      if (uri.toString().contains("fail")) {
        throw new FeedFetchException("fetch failed", 503);
      }
      return new FeedHttpResponse(200, """
          <?xml version="1.0" encoding="UTF-8"?>
          <rss version="2.0">
            <channel>
              <title>Ok Feed</title>
              <item>
                <title>Post</title>
                <link>https://example.com/posts/1</link>
              </item>
            </channel>
          </rss>
          """);
    });
    FeedUpdateService updateService = new FeedUpdateService(feedService, feedFetcher);

    List<FeedUpdateResult> results = updateService.updateAll();

    assertEquals(2, results.size());
    assertEquals(okFeed.id(), results.getFirst().feedId());
    assertEquals(1, feedService.listItemsForFeed(okFeed.id()).size());
    assertEquals(0, feedService.listItemsForFeed(disabledFeed.id()).size());
    assertEquals(1, feedService.listFailures(failingFeed.id()).size());
    assertEquals(503, feedService.listFailures(failingFeed.id()).getFirst().httpStatus());
  }

  @Test
  void listRecentItemsReturnsItemsSinceYesterdayStartSortedDescending() {
    FeedService feedService = newService();
    Feed feed = feedService.add("https://example.com/feed.xml", "Example Feed");
    OffsetDateTime fetchedAt = OffsetDateTime.of(2026, 4, 22, 8, 0, 0, 0, ZoneOffset.UTC);
    feedService.saveFetchedItems(feed.id(), List.of(
        new FetchedFeedItem("Today", "https://example.com/today", OffsetDateTime.of(2026, 4, 22, 7, 0, 0, 0, ZoneOffset.UTC)),
        new FetchedFeedItem("Yesterday", "https://example.com/yesterday", OffsetDateTime.of(2026, 4, 21, 3, 0, 0, 0, ZoneOffset.UTC)),
        new FetchedFeedItem("Old", "https://example.com/old", OffsetDateTime.of(2026, 4, 20, 23, 59, 59, 0, ZoneOffset.UTC))),
        fetchedAt);

    List<FeedBriefingItem> items = feedService.listBriefingItems(
        OffsetDateTime.of(2026, 4, 21, 0, 0, 0, 0, ZoneOffset.UTC),
        OffsetDateTime.of(2026, 4, 22, 9, 0, 0, 0, ZoneOffset.UTC),
        10);

    assertEquals(List.of("Today", "Yesterday"), items.stream().map(FeedBriefingItem::title).toList());
  }

  private FeedService newService() {
    return new FeedService(new DriverManagerDataSource("jdbc:sqlite:" + tempDir.resolve("feed-update.db")));
  }
}
