package dev.mikoto2000.rei.core.command;

import dev.mikoto2000.rei.event.ProfileEventLogStore;
import dev.mikoto2000.rei.event.ProfileEventLogStore.DurationStats;
import dev.mikoto2000.rei.event.ProfileEventLogStore.ProfileBucket;
import dev.mikoto2000.rei.event.ProfileEventLogStore.ProfileSummary;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** Agent Event のプロファイルログを表示しますを行うための利用者コマンド。 */
@Component
@Command(
    name = "profile",
    description = "Agent Event のプロファイルログを表示します",
    subcommands = {
      ProfileCommand.PathCommand.class,
      ProfileCommand.SummaryCommand.class,
      ProfileCommand.ChartCommand.class,
      ProfileCommand.MermaidCommand.class
    })
public class ProfileCommand implements Runnable {

  /** ログ保存先。 */
  private final ProfileEventLogStore logStore;

  /** 書き込み先。 */
  private final java.io.PrintWriter writer;

  /** 共通の実行基盤で用いる出力先を返す。 */
  private java.io.PrintWriter out() {
    return writer == null ? new java.io.PrintWriter(System.out, true) : writer;
  }

  /** 指定された値や利用先を使って、ProfileCommandを初期化する。 */
  private ProfileCommand(ProfileEventLogStore store, java.io.PrintWriter writer) {
    this.logStore = store;
    this.writer = writer;
  }

  /** 利用者が内容を確認できるよう、表示形式へ変換する。 */
  private String render(java.util.function.Consumer<ProfileCommand> report) {
    var text = new java.io.StringWriter();
    var output = new java.io.PrintWriter(text, true);
    report.accept(new ProfileCommand(logStore, output));
    output.flush();
    return text.toString();
  }

  /** 利用者が内容を確認できるよう、要約を表示する。 */
  public String renderSummary() {
    return render(ProfileCommand::printSummary);
  }

  /** 利用者が内容を確認できるよう、パスを表示する。 */
  public String renderPath() {
    return render(ProfileCommand::printPath);
  }

  /** 利用者が内容を確認できるよう、Chartを表示する。 */
  public String renderChart(long seconds, int width) {
    return render(command -> command.printChart(Math.max(1L, seconds), Math.max(1, width)));
  }

  /** 利用者が内容を確認できるよう、Mermaidを表示する。 */
  public String renderMermaid(int limit) {
    return render(command -> command.printMermaid(Math.max(1, limit)));
  }

  /** 共通の実行基盤を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public ProfileCommand() {
    this(new ProfileEventLogStore());
  }

  /** 指定された値や利用先を使って、ProfileCommandを初期化する。 */
  @Autowired
  public ProfileCommand(ProfileEventLogStore logStore) {
    this.logStore = logStore;
    this.writer = null;
  }

  /** Agent Event のプロファイルログを表示しますを行う。 */
  @Override
  public void run() {
    printSummary();
  }

  /** 利用者に結果を伝えるため、パスを出力する。 */
  void printPath() {
    out().println(logStore.file());
  }

  /** 利用者に結果を伝えるため、要約を出力する。 */
  void printSummary() {
    ProfileSummary summary =
        new dev.mikoto2000.rei.application.read.ProfileQueryService(logStore).summary();
    out().println("profile log: " + summary.file());
    out().println("events: " + summary.total());
    if (summary.total() == 0) {
      return;
    }
    out().println("from: " + summary.first());
    out().println("to: " + summary.last());
    out().println();
    out().println("counts:");
    summary.countsByType().entrySet().stream()
        .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
        .forEach(entry -> out().printf("  %s %d%n", entry.getKey(), entry.getValue()));
    if (!summary.durationsByType().isEmpty()) {
      out().println();
      out().println("durations(ms):");
      summary.durationsByType().entrySet().stream()
          .sorted(
              Comparator.<Map.Entry<String, DurationStats>>comparingLong(
                      entry -> entry.getValue().totalMillis())
                  .reversed())
          .forEach(entry -> printDuration(entry.getKey(), entry.getValue()));
    }
  }

  /** 利用者に結果を伝えるため、Chartを出力する。 */
  void printChart(long bucketSeconds, int width) {
    List<ProfileBucket> buckets = logStore.buckets(Duration.ofSeconds(bucketSeconds));
    if (buckets.isEmpty()) {
      out().println("profile log is empty: " + logStore.file());
      return;
    }
    long max = buckets.stream().mapToLong(ProfileBucket::count).max().orElse(1L);
    DateTimeFormatter timeFormat =
        DateTimeFormatter.ofPattern("HH:mm:ss").withZone(java.time.ZoneId.systemDefault());
    for (ProfileBucket bucket : buckets) {
      int barLength = (int) Math.max(1L, Math.round((double) bucket.count() / max * width));
      out()
          .printf(
              "%s | %s %d%n",
              timeFormat.format(bucket.start()), "#".repeat(barLength), bucket.count());
    }
  }

  /** 利用者に結果を伝えるため、Mermaidを出力する。 */
  void printMermaid(int limit) {
    ProfileSummary summary =
        new dev.mikoto2000.rei.application.read.ProfileQueryService(logStore).summary();
    if (summary.total() == 0) {
      out().println("profile log is empty: " + summary.file());
      return;
    }
    List<Map.Entry<String, Long>> entries =
        summary.countsByType().entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .limit(limit)
            .toList();
    out().println("```mermaid");
    out().println("xychart-beta");
    out().println("  title \"Agent Event counts\"");
    out()
        .println(
            "  x-axis ["
                + String.join(", ", entries.stream().map(entry -> quote(entry.getKey())).toList())
                + "]");
    out()
        .println(
            "  y-axis \"events\" 0 --> "
                + entries.stream().mapToLong(Map.Entry::getValue).max().orElse(1L));
    out()
        .println(
            "  bar ["
                + String.join(
                    ", ", entries.stream().map(entry -> Long.toString(entry.getValue())).toList())
                + "]");
    out().println("```");
  }

  /** 利用者に結果を伝えるため、継続時間を出力する。 */
  private void printDuration(String type, DurationStats stats) {
    out()
        .printf(
            "  %s count=%d avg=%d min=%d max=%d total=%d%n",
            type,
            stats.count(),
            stats.averageMillis(),
            stats.minMillis(),
            stats.maxMillis(),
            stats.totalMillis());
  }

  /** 共通の実行基盤で用いる引用を返す。 */
  private String quote(String value) {
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }

  /** プロファイルログのパスを表示しますを行うための利用者コマンド。 */
  @Component
  @Command(name = "path", description = "プロファイルログのパスを表示します")
  public static class PathCommand implements Runnable {
    /** 親。 */
    private final ProfileCommand parent;

    /** 指定された値や利用先を使って、PathCommandを初期化する。 */
    public PathCommand(ProfileCommand parent) {
      this.parent = parent;
    }

    /** プロファイルログのパスを表示しますを行う。 */
    @Override
    public void run() {
      parent.printPath();
    }
  }

  /** プロファイルログの集計を表示しますを行うための利用者コマンド。 */
  @Component
  @Command(name = "summary", description = "プロファイルログの集計を表示します")
  public static class SummaryCommand implements Runnable {
    /** 親。 */
    private final ProfileCommand parent;

    /** 指定された値や利用先を使って、SummaryCommandを初期化する。 */
    public SummaryCommand(ProfileCommand parent) {
      this.parent = parent;
    }

    /** プロファイルログの集計を表示しますを行う。 */
    @Override
    public void run() {
      parent.printSummary();
    }
  }

  /** イベント数の ASCII グラフを表示しますを行うための利用者コマンド。 */
  @Component
  @Command(name = "chart", description = "イベント数の ASCII グラフを表示します")
  public static class ChartCommand implements Runnable {
    /** 親。 */
    private final ProfileCommand parent;

    /** 集計区分秒数。 */
    @Option(names = "--bucket-seconds", description = "集計間隔の秒数")
    long bucketSeconds = 60L;

    /** 幅。 */
    @Option(names = "--width", description = "棒グラフの最大幅")
    int width = 40;

    /** 指定された値や利用先を使って、ChartCommandを初期化する。 */
    public ChartCommand(ProfileCommand parent) {
      this.parent = parent;
    }

    /** イベント数の ASCII グラフを表示しますを行う。 */
    @Override
    public void run() {
      parent.printChart(Math.max(1L, bucketSeconds), Math.max(1, width));
    }
  }

  /** イベント数の Mermaid グラフを表示しますを行うための利用者コマンド。 */
  @Component
  @Command(name = "mermaid", description = "イベント数の Mermaid グラフを表示します")
  public static class MermaidCommand implements Runnable {
    /** 親。 */
    private final ProfileCommand parent;

    /** 上限。 */
    @Option(names = "--limit", description = "表示するイベント種別数")
    int limit = 12;

    /** 指定された値や利用先を使って、MermaidCommandを初期化する。 */
    public MermaidCommand(ProfileCommand parent) {
      this.parent = parent;
    }

    /** イベント数の Mermaid グラフを表示しますを行う。 */
    @Override
    public void run() {
      parent.printMermaid(Math.max(1, limit));
    }
  }
}
