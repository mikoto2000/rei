package dev.mikoto2000.rei.feed.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import dev.mikoto2000.rei.feed.FeedService;
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

  private FeedService newService() {
    return new FeedService(new DriverManagerDataSource("jdbc:sqlite:" + tempDir.resolve("feed-command.db")));
  }

  private CommandLine newCommand(FeedService service) {
    return new CommandLine(new FeedCommand(), new CommandLine.IFactory() {
      @Override
      public <K> K create(Class<K> cls) throws Exception {
        if (cls == FeedCommand.AddCommand.class) {
          return cls.cast(new FeedCommand.AddCommand(service));
        }
        if (cls == FeedCommand.ListCommand.class) {
          return cls.cast(new FeedCommand.ListCommand(service));
        }
        return CommandLine.defaultFactory().create(cls);
      }
    });
  }
}
