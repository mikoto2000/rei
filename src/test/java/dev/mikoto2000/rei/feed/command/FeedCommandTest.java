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
import dev.mikoto2000.rei.feed.FeedService;
import dev.mikoto2000.rei.feed.FeedUpdateResult;
import dev.mikoto2000.rei.feed.FeedUpdateService;
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

  private FeedService newService() {
    return new FeedService(new DriverManagerDataSource("jdbc:sqlite:" + tempDir.resolve("feed-command.db")));
  }

  private CommandLine newCommand(FeedService service) {
    FeedUpdateService updateService = new FeedUpdateService(service, new FeedFetcher(uri -> {
      throw new UnsupportedOperationException("updateService is not configured for this test");
    }));
    return newCommand(service, updateService);
  }

  private CommandLine newCommand(FeedService service, FeedUpdateService updateService) {
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
        return CommandLine.defaultFactory().create(cls);
      }
    });
  }
}
