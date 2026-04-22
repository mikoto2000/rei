package dev.mikoto2000.rei.feed.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import dev.mikoto2000.rei.feed.Feed;
import dev.mikoto2000.rei.feed.FeedFetcher;
import dev.mikoto2000.rei.feed.FeedHttpResponse;
import dev.mikoto2000.rei.feed.FeedProperties;
import dev.mikoto2000.rei.feed.FeedService;
import dev.mikoto2000.rei.feed.FeedSummaryService;
import dev.mikoto2000.rei.feed.FeedUpdateResult;
import dev.mikoto2000.rei.feed.FeedUpdateService;
import dev.mikoto2000.rei.websearch.WebSearchPage;
import picocli.CommandLine;

class FeedCommandTest {

  @TempDir
  Path tempDir;

  @Test
  void addCommandCreatesFeed() {
    FeedService service = newService();

    int exitCode = newCommand(service).execute("add", "--name", "Example Feed", "https://example.com/feed.xml");

    assertEquals(0, exitCode);
    assertEquals(1, service.list().size());
    assertEquals("Example Feed", service.list().getFirst().displayName());
  }

  @Test
  void listCommandPrintsEmptyMessageWhenNoFeeds() {
    FeedService service = newService();

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    System.setOut(new PrintStream(out));
    try {
      assertEquals(0, newCommand(service).execute("list"));
    } finally {
      System.setOut(originalOut);
    }

    assertTrue(out.toString().contains("登録済みフィードはありません"));
  }

  @Test
  void listCommandPrintsRegisteredFeeds() {
    FeedService service = newService();
    newCommand(service).execute("add", "--name", "Example Feed", "https://example.com/feed.xml");

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    System.setOut(new PrintStream(out));
    try {
      assertEquals(0, newCommand(service).execute("list"));
    } finally {
      System.setOut(originalOut);
    }

    String output = out.toString();
    assertTrue(output.contains("Example Feed"));
    assertTrue(output.contains("https://example.com/feed.xml"));
  }

  @Test
  void editCommandUpdatesDisplayNameAndEnabledFlag() {
    FeedService service = newService();
    newCommand(service).execute("add", "--name", "Before", "https://example.com/feed.xml");
    long id = service.list().getFirst().id();

    assertEquals(0, newCommand(service).execute("edit", "--name", "After", "--disabled", Long.toString(id)));

    Feed updated = service.list().getFirst();
    assertEquals("After", updated.displayName());
    assertEquals(false, updated.enabled());
  }

  @Test
  void deleteCommandRemovesFeed() {
    FeedService service = newService();
    newCommand(service).execute("add", "--name", "Delete Me", "https://example.com/feed.xml");
    long id = service.list().getFirst().id();

    assertEquals(0, newCommand(service).execute("delete", Long.toString(id)));
    assertEquals(0, service.list().size());
  }

  @Test
  void updateCommandUpdatesAllFeedsAndPrintsResults() {
    FeedService service = newService();
    service.add("https://example.com/feed.xml", "Example Feed");
    FeedUpdateService updateService = new FeedUpdateService(service, new FeedFetcher(uri -> new FeedHttpResponse(200, """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0">
          <channel>
            <title>Fetched Title</title>
            <item>
              <title>First Post</title>
              <link>https://example.com/posts/1</link>
              <pubDate>Tue, 21 Apr 2026 09:30:00 GMT</pubDate>
            </item>
          </channel>
        </rss>
        """)));

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    System.setOut(new PrintStream(out));
    try {
      assertEquals(0, newCommand(service, updateService).execute("update"));
    } finally {
      System.setOut(originalOut);
    }

    assertTrue(out.toString().contains("更新: Example Feed | +1"));
    assertEquals(1, service.listItemsForFeed(service.list().getFirst().id()).size());
  }

  @Test
  void summaryCommandPrintsBriefingSummary() {
    FeedService service = newService();
    Feed feed = service.add("https://example.com/feed.xml", "Example Feed");
    service.saveFetchedItems(feed.id(), List.of(
        new dev.mikoto2000.rei.feed.FetchedFeedItem(
            "Today",
            "https://example.com/today",
            OffsetDateTime.now(ZoneOffset.UTC))),
        OffsetDateTime.now(ZoneOffset.UTC));
    FeedSummaryService summaryService = new FeedSummaryService(
        service,
        item -> new WebSearchPage(item.title(), item.url(), "", item.publishedAt() == null ? null : item.publishedAt().toString(), "Fetched body"),
        prompt -> "briefing summary",
        new FeedProperties(20));

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    System.setOut(new PrintStream(out));
    try {
      assertEquals(0, newCommand(service, defaultUpdateService(service), summaryService).execute("summary"));
    } finally {
      System.setOut(originalOut);
    }

    assertTrue(out.toString().contains("briefing summary"));
  }

  @Test
  void itemListCommandPrintsRecentItemsWithIds() {
    FeedService service = newService();
    Feed feed = service.add("https://example.com/feed.xml", "Example Feed");
    service.saveFetchedItems(feed.id(), List.of(
        new dev.mikoto2000.rei.feed.FetchedFeedItem(
            "Today",
            "https://example.com/today",
            OffsetDateTime.of(2026, 4, 22, 7, 0, 0, 0, ZoneOffset.UTC)),
        new dev.mikoto2000.rei.feed.FetchedFeedItem(
            "Yesterday",
            "https://example.com/yesterday",
            OffsetDateTime.of(2026, 4, 21, 3, 0, 0, 0, ZoneOffset.UTC))),
        OffsetDateTime.of(2026, 4, 22, 8, 0, 0, 0, ZoneOffset.UTC));

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    System.setOut(new PrintStream(out));
    try {
      assertEquals(0, newCommand(service).execute("item", "list", "--from", "2026-04-21T00:00:00Z", "--to", "2026-04-22T09:00:00Z"));
    } finally {
      System.setOut(originalOut);
    }

    String output = out.toString();
    assertTrue(output.contains("Today"));
    assertTrue(output.contains("Yesterday"));
    assertTrue(output.contains("Example Feed"));
    assertTrue(output.contains("https://example.com/today"));
  }

  private FeedService newService() {
    return new FeedService(new DriverManagerDataSource("jdbc:sqlite:" + tempDir.resolve("feed-command.db")));
  }

  private CommandLine newCommand(FeedService service) {
    return newCommand(service, defaultUpdateService(service), defaultSummaryService(service));
  }

  private CommandLine newCommand(FeedService service, FeedUpdateService updateService) {
    return newCommand(service, updateService, defaultSummaryService(service));
  }

  private CommandLine newCommand(FeedService service, FeedUpdateService updateService, FeedSummaryService summaryService) {
    return new CommandLine(new FeedCommand(), new CommandLine.IFactory() {
      @Override
      public <K> K create(Class<K> cls) throws Exception {
        if (cls == FeedCommand.AddCommand.class) {
          return cls.cast(new FeedCommand.AddCommand(service));
        }
        if (cls == FeedCommand.ListCommand.class) {
          return cls.cast(new FeedCommand.ListCommand(service));
        }
        if (cls == FeedCommand.EditCommand.class) {
          return cls.cast(new FeedCommand.EditCommand(service));
        }
        if (cls == FeedCommand.DeleteCommand.class) {
          return cls.cast(new FeedCommand.DeleteCommand(service));
        }
        if (cls == FeedCommand.UpdateCommand.class) {
          return cls.cast(new FeedCommand.UpdateCommand(updateService));
        }
        if (cls == FeedCommand.SummaryCommand.class) {
          return cls.cast(new FeedCommand.SummaryCommand(summaryService));
        }
        if (cls == FeedCommand.ItemCommand.SummarizeCommand.class) {
          return cls.cast(new FeedCommand.ItemCommand.SummarizeCommand(summaryService));
        }
        if (cls == FeedCommand.ItemCommand.ListCommand.class) {
          return cls.cast(new FeedCommand.ItemCommand.ListCommand(service));
        }
        return CommandLine.defaultFactory().create(cls);
      }
    });
  }

  private FeedUpdateService defaultUpdateService(FeedService service) {
    return new FeedUpdateService(service, new FeedFetcher(uri -> {
      throw new UnsupportedOperationException("updateService is not configured for this test");
    }));
  }

  private FeedSummaryService defaultSummaryService(FeedService service) {
    return new FeedSummaryService(
        service,
        item -> new WebSearchPage(item.title(), item.url(), "", item.publishedAt() == null ? null : item.publishedAt().toString(), "Fetched body"),
        prompt -> "summary",
        new FeedProperties(20));
  }
}
