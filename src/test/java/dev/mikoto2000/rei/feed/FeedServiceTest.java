package dev.mikoto2000.rei.feed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class FeedServiceTest {

  @TempDir
  Path tempDir;

  @Test
  void savesEmbeddedFeedContentForSummaryFallback() {
    FeedService service = newService();
    Feed feed = service.add("https://example.com/feed.xml", "Example");
    service.saveFetchedItems(feed.id(), List.of(new FetchedFeedItem(
        "Title", "https://example.com/article", OffsetDateTime.parse("2026-04-22T01:23:45Z"),
        "Description marker", "Embedded content marker")), OffsetDateTime.parse("2026-04-22T02:00:00Z"));

    FeedItem item = service.listItemsForFeed(feed.id()).getFirst();

    assertEquals("Description marker", item.description());
    assertEquals("Embedded content marker", item.content());
  }

  @Test
  void addAndListFeeds() {
    FeedService service = newService();

    Feed created = service.add("https://example.com/feed.xml", "Example Feed");

    assertEquals(1, service.list().size());
    assertEquals(created, service.list().getFirst());
    assertTrue(created.enabled());
    assertEquals("https://example.com/feed.xml", created.url());
    assertEquals("Example Feed", created.displayName());
  }

  @Test
  void addRejectsDuplicateFeedUrl() {
    FeedService service = newService();
    service.add("https://example.com/feed.xml", "Example Feed");

    IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
        () -> service.add("https://example.com/feed.xml", "Duplicate"));

    assertEquals("同じフィード URL は登録できません", error.getMessage());
  }

  private FeedService newService() {
    return new FeedService(new DriverManagerDataSource("jdbc:sqlite:" + tempDir.resolve("feed.db")));
  }
}
