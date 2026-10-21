package dev.mikoto2000.rei.feed.command;

import dev.mikoto2000.rei.core.project.ProjectService;
import dev.mikoto2000.rei.feed.Feed;
import dev.mikoto2000.rei.feed.FeedBriefingItem;
import dev.mikoto2000.rei.feed.FeedOpmlImportResult;
import dev.mikoto2000.rei.feed.FeedOpmlImportService;
import dev.mikoto2000.rei.feed.FeedService;
import dev.mikoto2000.rei.feed.FeedSummaryService;
import dev.mikoto2000.rei.feed.FeedUpdateResult;
import dev.mikoto2000.rei.feed.FeedUpdateService;
import dev.mikoto2000.rei.feed.OpmlImportException;
import dev.mikoto2000.rei.ui.shell.sound.ChatResponseNarrator;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Callable;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/** RSS/Atom フィードを操作しますを行うための利用者コマンド。 */
@Component
@Command(
    name = "feed",
    description = "RSS/Atom フィードを操作します",
    subcommands = {
      FeedCommand.AddCommand.class,
      FeedCommand.ImportOpmlCommand.class,
      FeedCommand.ListCommand.class,
      FeedCommand.EditCommand.class,
      FeedCommand.DeleteCommand.class,
      FeedCommand.UpdateCommand.class,
      FeedCommand.SummaryCommand.class,
      FeedCommand.ItemCommand.class
    })
public class FeedCommand {
  /** フィードの購読と収集のうち、レポートの操作を利用者コマンドとして受け付ける。 */
  private abstract static class ReportCommand implements Runnable {
    /** 出力先。 */
    protected java.io.PrintWriter out = new java.io.PrintWriter(System.out, true);
  }

  /** フィードの購読と収集で用いるレポートを返す。 */
  private static String report(ReportCommand command) {
    var text = new java.io.StringWriter();
    command.out = new java.io.PrintWriter(text, true);
    command.run();
    return text.toString().replaceAll("[\\r\\n]+$", "");
  }

  /** フィードの購読と収集で用いるレポート一覧を返す。 */
  public static String reportList(FeedService service) {
    return report(new ListCommand(service));
  }

  /** フィードの購読と収集で用いるレポート追加を返す。 */
  public static String reportAdd(FeedService service, String url, String name) {
    var command = new AddCommand(service);
    command.url = url;
    command.displayName = name;
    return report(command);
  }

  /** フィードの購読と収集で用いるレポート編集を返す。 */
  public static String reportEdit(FeedService service, long id, String name, Boolean enabled) {
    var command = new EditCommand(service);
    command.id = id;
    command.displayName = name;
    command.enabled = Boolean.TRUE.equals(enabled);
    command.disabled = Boolean.FALSE.equals(enabled);
    return report(command);
  }

  /** フィードの購読と収集で用いるレポート削除を返す。 */
  public static String reportDelete(FeedService service, long id) {
    var command = new DeleteCommand(service);
    command.id = id;
    return report(command);
  }

  /** フィードの購読と収集で用いるレポート更新を返す。 */
  public static String reportUpdate(FeedUpdateService service, Long id) {
    var command = new UpdateCommand(service);
    command.id = id;
    return report(command);
  }

  /** フィードの購読と収集で用いるレポート要約を返す。 */
  public static String reportSummary(FeedSummaryService service) {
    var command = new SummaryCommand(service, null);
    return report(command);
  }

  /** フィードの購読と収集で用いるレポート項目一覧を返す。 */
  public static String reportItems(
      FeedService service, OffsetDateTime from, OffsetDateTime to, int limit) {
    var command = new ItemCommand.ListCommand(service);
    command.from = from;
    command.to = to;
    command.limit = limit;
    return report(command);
  }

  /** フィードの購読と収集で用いるレポート項目要約を返す。 */
  public static String reportItemSummary(FeedSummaryService service, long id) {
    var command = new ItemCommand.SummarizeCommand(service);
    command.itemId = id;
    return report(command);
  }

  /** フィードの購読と収集で用いるレポート取り込みを返す。 */
  public static String reportImport(FeedOpmlImportService service, String path) {
    var command = new ImportOpmlCommand(service);
    command.path = path;
    var text = new java.io.StringWriter();
    var writer = new java.io.PrintWriter(text, true);
    new picocli.CommandLine(command).setOut(writer).setErr(writer);
    command.call();
    return text.toString().replaceAll("[\\r\\n]+$", "");
  }

  /** OPML ファイルからフィードを一括登録しますを行うための利用者コマンド。 */
  @Component
  @Command(name = "import-opml", description = "OPML ファイルからフィードを一括登録します")
  public static class ImportOpmlCommand implements Callable<Integer> {
    /** 最大詳細一覧。 */
    private static final int MAX_DETAILS = 20;

    /** 取り込みサービス。 */
    private final FeedOpmlImportService importService;

    /**
     * フィードの購読と収集を利用するため、指定を補って共通の初期化へ引き渡す。
     *
     * <p>Used by picocli for command discovery and help without the Spring context.
     */
    public ImportOpmlCommand() {
      this(null);
    }

    /** 指定された値や利用先を使って、ImportOpmlCommandを初期化する。 */
    @org.springframework.beans.factory.annotation.Autowired
    public ImportOpmlCommand(FeedOpmlImportService importService) {
      this.importService = importService;
    }

    /** 仕様。 */
    @Spec CommandSpec spec;

    /** パス。 */
    @Parameters(
        index = "0",
        paramLabel = "PATH",
        description = "OPML ファイルのパス",
        completionCandidates =
            dev.mikoto2000.rei.core.completion.PathCompletionCandidates.File.class)
    String path;

    /** OPML ファイルからフィードを一括登録しますを行う。 */
    @Override
    public Integer call() {
      try {
        if (importService == null)
          throw new OpmlImportException("feed import service is unavailable");
        FeedOpmlImportResult result = importService.importFile(resolvePath());
        var out = spec.commandLine().getOut();
        out.println("OPML import completed.");
        out.println();
        out.println("Imported: " + result.imported().size());
        out.println("Skipped: " + result.skipped().size());
        out.println("Failed: " + result.failed().size());
        printDetails("Skipped", result.skipped());
        printDetails("Failed", result.failed());
        return 0;
      } catch (OpmlImportException e) {
        spec.commandLine().getErr().println("Failed to import OPML: " + singleLine(e.getMessage()));
        return 1;
      } catch (InvalidPathException e) {
        spec.commandLine().getErr().println("Failed to import OPML: invalid file path");
        return 1;
      }
    }

    /** 指定や設定に応じた対象を利用するため、パスを確定する。 */
    private Path resolvePath() {
      if (path.equals("~")) return Path.of(System.getProperty("user.home"));
      if (path.startsWith("~/") || path.startsWith("~\\")) {
        return Path.of(System.getProperty("user.home")).resolve(path.substring(2));
      }
      Path input = Path.of(path);
      return (input.isAbsolute()
              ? input
              : ProjectService.currentProjectOrStartupDirectory().resolve(input))
          .normalize();
    }

    /** 利用者に結果を伝えるため、詳細一覧を出力する。 */
    private void printDetails(String label, List<FeedOpmlImportResult.Entry> entries) {
      if (entries.isEmpty()) return;
      var out = spec.commandLine().getOut();
      out.println();
      out.println(label + ":");
      entries.stream()
          .limit(MAX_DETAILS)
          .forEach(
              entry ->
                  out.println(
                      "- "
                          + singleLine(entry.subscription().xmlUrl())
                          + " ("
                          + entry.reason()
                          + ")"));
      // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
      if (entries.size() > MAX_DETAILS)
        out.println("... and " + (entries.size() - MAX_DETAILS) + " more");
    }

    /** フィードの購読と収集で用いる単一行を返す。 */
    private String singleLine(String value) {
      return value.replaceAll("[\\p{Cntrl}\\p{Zl}\\p{Zp}]", " ");
    }
  }

  /** フィードを追加しますを行うための利用者コマンド。 */
  @Component
  @RequiredArgsConstructor
  @Command(name = "add", description = "フィードを追加します")
  public static class AddCommand extends ReportCommand {

    /** フィードサービス。 */
    private final FeedService feedService;

    /** 表示名前。 */
    @Option(names = "--name", description = "表示名")
    String displayName;

    /** 参照先のURL。 */
    @Parameters(paramLabel = "URL", description = "フィード URL")
    String url;

    /** フィードを追加しますを行う。 */
    @Override
    public void run() {
      Feed created = feedService.add(url, displayName);
      String resolvedName =
          created.displayName() == null || created.displayName().isBlank()
              ? "-"
              : created.displayName();
      out.println("追加: " + created.id() + " | " + resolvedName + " | " + created.url());
    }
  }

  /** 登録済みフィードを一覧しますを行うための利用者コマンド。 */
  @Component
  @RequiredArgsConstructor
  @Command(name = "list", description = "登録済みフィードを一覧します")
  public static class ListCommand extends ReportCommand {

    /** フィードサービス。 */
    private final FeedService feedService;

    /** 登録済みフィードを一覧しますを行う。 */
    @Override
    public void run() {
      List<Feed> feeds = feedService.list();
      if (feeds.isEmpty()) {
        out.println("登録済みフィードはありません");
        return;
      }

      for (Feed feed : feeds) {
        String displayName =
            feed.displayName() == null || feed.displayName().isBlank() ? "-" : feed.displayName();
        String lastFetchedAt = feed.lastFetchedAt() == null ? "-" : feed.lastFetchedAt().toString();
        out.println(
            displayName
                + " | "
                + feed.url()
                + " | "
                + lastFetchedAt
                + " | "
                + (feed.enabled() ? "enabled" : "disabled"));
      }
    }
  }

  /** フィード設定を更新しますを行うための利用者コマンド。 */
  @Component
  @RequiredArgsConstructor
  @Command(name = "edit", description = "フィード設定を更新します")
  public static class EditCommand extends ReportCommand {

    /** フィードサービス。 */
    private final FeedService feedService;

    /** 表示名前。 */
    @Option(names = "--name", description = "表示名")
    String displayName;

    /** 有効状態。 */
    @Option(names = "--enabled", description = "有効にします")
    boolean enabled;

    /** 無効状態。 */
    @Option(names = "--disabled", description = "無効にします")
    boolean disabled;

    /** 識別子。 */
    @Parameters(paramLabel = "ID", description = "フィード ID")
    long id;

    /** フィード設定を更新しますを行う。 */
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
      String resolvedName =
          updated.displayName() == null || updated.displayName().isBlank()
              ? "-"
              : updated.displayName();
      out.println(
          "更新: "
              + updated.id()
              + " | "
              + resolvedName
              + " | "
              + (updated.enabled() ? "enabled" : "disabled"));
    }
  }

  /** フィードを削除しますを行うための利用者コマンド。 */
  @Component
  @RequiredArgsConstructor
  @Command(name = "delete", description = "フィードを削除します")
  public static class DeleteCommand extends ReportCommand {

    /** フィードサービス。 */
    private final FeedService feedService;

    /** 識別子。 */
    @Parameters(paramLabel = "ID", description = "フィード ID")
    long id;

    /** フィードを削除しますを行う。 */
    @Override
    public void run() {
      feedService.delete(id);
      out.println("削除: " + id);
    }
  }

  /** フィードを更新しますを行うための利用者コマンド。 */
  @Component
  @RequiredArgsConstructor
  @Command(name = "update", description = "フィードを更新します")
  public static class UpdateCommand extends ReportCommand {

    /** フィード更新サービス。 */
    private final FeedUpdateService feedUpdateService;

    /** 識別子。 */
    @Parameters(arity = "0..1", paramLabel = "ID", description = "指定時は単一フィードのみ更新")
    Long id;

    /** フィードを更新しますを行う。 */
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

    /** 利用者に結果を伝えるため、更新コマンドを出力する。 */
    private void print(FeedUpdateResult result) {
      if (result.errorMessage() == null || result.errorMessage().isBlank()) {
        out.println("更新: " + result.feedName() + " | +" + result.addedItems());
      } else {
        out.println("更新失敗: " + result.feedName() + " | " + result.errorMessage());
      }
    }
  }

  /** 新着記事ブリーフィングを要約しますを行うための利用者コマンド。 */
  @Component
  @RequiredArgsConstructor
  @Command(name = "summary", description = "新着記事ブリーフィングを要約します")
  public static class SummaryCommand extends ReportCommand {

    /** フィード要約サービス。 */
    private final FeedSummaryService feedSummaryService;

    /** チャット応答読み上げの案内。 */
    private final ChatResponseNarrator chatResponseNarrator;

    /** 新着記事ブリーフィングを要約しますを行う。 */
    @Override
    public void run() {
      if (chatResponseNarrator != null) chatResponseNarrator.reset();
      String summary = feedSummaryService.summarizeBriefing();
      out.println(summary);
      if (chatResponseNarrator != null) chatResponseNarrator.narrateIfCompleted(summary);
    }
  }

  /** 個別記事を操作しますを行うための利用者コマンド。 */
  @Component
  @Command(
      name = "item",
      description = "個別記事を操作します",
      subcommands = {
        FeedCommand.ItemCommand.ListCommand.class,
        FeedCommand.ItemCommand.SummarizeCommand.class
      })
  public static class ItemCommand {

    /** 要約対象の記事 ID 一覧を表示しますを行うための利用者コマンド。 */
    @Component
    @RequiredArgsConstructor
    @Command(name = "list", description = "要約対象の記事 ID 一覧を表示します")
    public static class ListCommand extends ReportCommand {

      /** フィードサービス。 */
      private final FeedService feedService;

      /** 起点。 */
      @Option(names = "--from", description = "開始日時。形式: 2026-04-21T00:00:00Z")
      OffsetDateTime from;

      /** 終点。 */
      @Option(names = "--to", description = "終了日時。形式: 2026-04-22T09:00:00Z")
      OffsetDateTime to;

      /** 上限。 */
      @Option(names = "--limit", defaultValue = "10", description = "表示件数")
      int limit;

      /** 登録済みフィードを一覧しますを行う。 */
      @Override
      public void run() {
        OffsetDateTime resolvedFrom =
            from == null
                ? OffsetDateTime.now(ZoneOffset.UTC)
                    .toLocalDate()
                    .minusDays(1)
                    .atStartOfDay()
                    .atOffset(ZoneOffset.UTC)
                : from;
        OffsetDateTime resolvedTo = to == null ? OffsetDateTime.now(ZoneOffset.UTC) : to;
        List<FeedBriefingItem> items =
            feedService.listBriefingItems(resolvedFrom, resolvedTo, limit);
        if (items.isEmpty()) {
          out.println("対象期間の記事はありません");
          return;
        }
        for (FeedBriefingItem item : items) {
          out.println(
              item.id()
                  + " | "
                  + item.publishedAt()
                  + " | "
                  + item.feedName()
                  + " | "
                  + item.title()
                  + " | "
                  + item.url());
        }
      }
    }

    /** 指定した記事を要約しますを行うための利用者コマンド。 */
    @Component
    @RequiredArgsConstructor
    @Command(name = "summarize", description = "指定した記事を要約します")
    public static class SummarizeCommand extends ReportCommand {

      /** フィード要約サービス。 */
      private final FeedSummaryService feedSummaryService;

      /** 項目識別子。 */
      @Parameters(paramLabel = "ITEM_ID", description = "記事 ID")
      long itemId;

      /** 指定した記事を要約しますを行う。 */
      @Override
      public void run() {
        out.println(feedSummaryService.summarizeItem(itemId));
      }
    }
  }
}
