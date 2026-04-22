package dev.mikoto2000.rei.feed.command;

import java.util.List;

import org.springframework.stereotype.Component;

import dev.mikoto2000.rei.feed.Feed;
import dev.mikoto2000.rei.feed.FeedService;
import lombok.RequiredArgsConstructor;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Component
@Command(
    name = "feed",
    description = "RSS/Atom フィードを操作します",
    subcommands = {
      FeedCommand.AddCommand.class,
      FeedCommand.ListCommand.class
    })
public class FeedCommand {

  @Component
  @RequiredArgsConstructor
  @Command(name = "add", description = "フィードを追加します")
  public static class AddCommand implements Runnable {

    private final FeedService feedService;

    @Option(names = "--name", description = "表示名")
    String displayName;

    @Parameters(paramLabel = "URL", description = "フィード URL")
    String url;

    @Override
    public void run() {
      Feed created = feedService.add(url, displayName);
      String resolvedName = created.displayName() == null || created.displayName().isBlank() ? "-" : created.displayName();
      System.out.println("追加: " + created.id() + " | " + resolvedName + " | " + created.url());
    }
  }

  @Component
  @RequiredArgsConstructor
  @Command(name = "list", description = "登録済みフィードを一覧します")
  public static class ListCommand implements Runnable {

    private final FeedService feedService;

    @Override
    public void run() {
      List<Feed> feeds = feedService.list();
      if (feeds.isEmpty()) {
        System.out.println("登録済みフィードはありません");
        return;
      }

      for (Feed feed : feeds) {
        String displayName = feed.displayName() == null || feed.displayName().isBlank() ? "-" : feed.displayName();
        String lastFetchedAt = feed.lastFetchedAt() == null ? "-" : feed.lastFetchedAt().toString();
        System.out.println(displayName + " | " + feed.url() + " | " + lastFetchedAt + " | " + (feed.enabled() ? "enabled" : "disabled"));
      }
    }
  }
}
