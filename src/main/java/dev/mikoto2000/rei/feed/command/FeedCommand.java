package dev.mikoto2000.rei.feed.command;

import java.util.List;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.springframework.stereotype.Component;

import dev.mikoto2000.rei.feed.Feed;
import dev.mikoto2000.rei.feed.FeedBriefingItem;
import dev.mikoto2000.rei.feed.FeedService;
import dev.mikoto2000.rei.feed.FeedSummaryService;
import dev.mikoto2000.rei.feed.FeedUpdateResult;
import dev.mikoto2000.rei.feed.FeedUpdateService;
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
      FeedCommand.ListCommand.class,
      FeedCommand.EditCommand.class,
      FeedCommand.DeleteCommand.class,
      FeedCommand.UpdateCommand.class,
      FeedCommand.SummaryCommand.class,
      FeedCommand.ItemCommand.class
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

  @Component
  @RequiredArgsConstructor
  @Command(name = "edit", description = "フィード設定を更新します")
  public static class EditCommand implements Runnable {

    private final FeedService feedService;

    @Option(names = "--name", description = "表示名")
    String displayName;

    @Option(names = "--enabled", description = "有効にします")
    boolean enabled;

    @Option(names = "--disabled", description = "無効にします")
    boolean disabled;

    @Parameters(paramLabel = "ID", description = "フィード ID")
    long id;

    @Override
    public void run() {
      Boolean resolvedEnabled = null;
      if (enabled == disabled) {
        resolvedEnabled = null;
      } else if (enabled) {
        resolvedEnabled = true;
      } else {
        resolvedEnabled = false;
      }
      Feed updated = feedService.update(id, displayName, resolvedEnabled);
      String resolvedName = updated.displayName() == null || updated.displayName().isBlank() ? "-" : updated.displayName();
      System.out.println("更新: " + updated.id() + " | " + resolvedName + " | " + (updated.enabled() ? "enabled" : "disabled"));
    }
  }

  @Component
  @RequiredArgsConstructor
  @Command(name = "delete", description = "フィードを削除します")
  public static class DeleteCommand implements Runnable {

    private final FeedService feedService;

    @Parameters(paramLabel = "ID", description = "フィード ID")
    long id;

    @Override
    public void run() {
      feedService.delete(id);
      System.out.println("削除: " + id);
    }
  }

  @Component
  @RequiredArgsConstructor
  @Command(name = "update", description = "フィードを更新します")
  public static class UpdateCommand implements Runnable {

    private final FeedUpdateService feedUpdateService;

    @Parameters(arity = "0..1", paramLabel = "ID", description = "指定時は単一フィードのみ更新")
    Long id;

    @Override
    public void run() {
      if (id == null) {
        for (FeedUpdateResult result : feedUpdateService.updateAll()) {
          print(result);
        }
        return;
      }
      print(feedUpdateService.update(id));
    }

    private void print(FeedUpdateResult result) {
      if (result.errorMessage() == null || result.errorMessage().isBlank()) {
        System.out.println("更新: " + result.feedName() + " | +" + result.addedItems());
      } else {
        System.out.println("更新失敗: " + result.feedName() + " | " + result.errorMessage());
      }
    }
  }

  @Component
  @RequiredArgsConstructor
  @Command(name = "summary", description = "新着記事ブリーフィングを要約します")
  public static class SummaryCommand implements Runnable {

    private final FeedSummaryService feedSummaryService;

    @Override
    public void run() {
      System.out.println(feedSummaryService.summarizeBriefing());
    }
  }

  @Component
  @Command(
      name = "item",
      description = "個別記事を操作します",
      subcommands = {
        FeedCommand.ItemCommand.ListCommand.class,
        FeedCommand.ItemCommand.SummarizeCommand.class
      })
  public static class ItemCommand {

    @Component
    @RequiredArgsConstructor
    @Command(name = "list", description = "要約対象の記事 ID 一覧を表示します")
    public static class ListCommand implements Runnable {

      private final FeedService feedService;

      @Option(names = "--from", description = "開始日時。形式: 2026-04-21T00:00:00Z")
      OffsetDateTime from;

      @Option(names = "--to", description = "終了日時。形式: 2026-04-22T09:00:00Z")
      OffsetDateTime to;

      @Option(names = "--limit", defaultValue = "10", description = "表示件数")
      int limit;

      @Override
      public void run() {
        OffsetDateTime resolvedFrom = from == null
            ? OffsetDateTime.now(ZoneOffset.UTC).toLocalDate().minusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC)
            : from;
        OffsetDateTime resolvedTo = to == null ? OffsetDateTime.now(ZoneOffset.UTC) : to;
        List<FeedBriefingItem> items = feedService.listBriefingItems(resolvedFrom, resolvedTo, limit);
        if (items.isEmpty()) {
          System.out.println("対象期間の記事はありません");
          return;
        }
        for (FeedBriefingItem item : items) {
          System.out.println(item.id() + " | " + item.publishedAt() + " | " + item.feedName() + " | " + item.title() + " | " + item.url());
        }
      }
    }

    @Component
    @RequiredArgsConstructor
    @Command(name = "summarize", description = "指定した記事を要約します")
    public static class SummarizeCommand implements Runnable {

      private final FeedSummaryService feedSummaryService;

      @Parameters(paramLabel = "ITEM_ID", description = "記事 ID")
      long itemId;

      @Override
      public void run() {
        System.out.println(feedSummaryService.summarizeItem(itemId));
      }
    }
  }
}
