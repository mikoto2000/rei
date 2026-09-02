package dev.mikoto2000.rei.core.command;

import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import dev.mikoto2000.rei.event.ProfileEventLogStore;
import dev.mikoto2000.rei.event.ProfileEventLogStore.DurationStats;
import dev.mikoto2000.rei.event.ProfileEventLogStore.ProfileBucket;
import dev.mikoto2000.rei.event.ProfileEventLogStore.ProfileSummary;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Component
@Command(name = "profile", description = "Agent Event のプロファイルログを表示します", subcommands = {
    ProfileCommand.PathCommand.class,
    ProfileCommand.SummaryCommand.class,
    ProfileCommand.ChartCommand.class,
    ProfileCommand.MermaidCommand.class
})
public class ProfileCommand implements Runnable {

  private final ProfileEventLogStore logStore;

  public ProfileCommand() {
    this(new ProfileEventLogStore());
  }

  @Autowired
  public ProfileCommand(ProfileEventLogStore logStore) {
    this.logStore = logStore;
  }

  @Override
  public void run() {
    printSummary();
  }

  void printPath() {
    System.out.println(logStore.file());
  }

  void printSummary() {
    ProfileSummary summary = logStore.summarize();
    System.out.println("profile log: " + summary.file());
    System.out.println("events: " + summary.total());
    if (summary.total() == 0) {
      return;
    }
    System.out.println("from: " + summary.first());
    System.out.println("to: " + summary.last());
    System.out.println();
    System.out.println("counts:");
    summary.countsByType().entrySet().stream()
        .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
        .forEach(entry -> System.out.printf("  %s %d%n", entry.getKey(), entry.getValue()));
    if (!summary.durationsByType().isEmpty()) {
      System.out.println();
      System.out.println("durations(ms):");
      summary.durationsByType().entrySet().stream()
          .sorted(Comparator.<Map.Entry<String, DurationStats>>comparingLong(entry -> entry.getValue().totalMillis())
              .reversed())
          .forEach(entry -> printDuration(entry.getKey(), entry.getValue()));
    }
  }

  void printChart(long bucketSeconds, int width) {
    List<ProfileBucket> buckets = logStore.buckets(Duration.ofSeconds(bucketSeconds));
    if (buckets.isEmpty()) {
      System.out.println("profile log is empty: " + logStore.file());
      return;
    }
    long max = buckets.stream().mapToLong(ProfileBucket::count).max().orElse(1L);
    DateTimeFormatter timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss")
        .withZone(java.time.ZoneId.systemDefault());
    for (ProfileBucket bucket : buckets) {
      int barLength = (int) Math.max(1L, Math.round((double) bucket.count() / max * width));
      System.out.printf("%s | %s %d%n", timeFormat.format(bucket.start()), "#".repeat(barLength), bucket.count());
    }
  }

  void printMermaid(int limit) {
    ProfileSummary summary = logStore.summarize();
    if (summary.total() == 0) {
      System.out.println("profile log is empty: " + summary.file());
      return;
    }
    List<Map.Entry<String, Long>> entries = summary.countsByType().entrySet().stream()
        .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
        .limit(limit)
        .toList();
    System.out.println("```mermaid");
    System.out.println("xychart-beta");
    System.out.println("  title \"Agent Event counts\"");
    System.out.println("  x-axis [" + String.join(", ", entries.stream().map(entry -> quote(entry.getKey())).toList()) + "]");
    System.out.println("  y-axis \"events\" 0 --> " + entries.stream().mapToLong(Map.Entry::getValue).max().orElse(1L));
    System.out.println("  bar [" + String.join(", ", entries.stream().map(entry -> Long.toString(entry.getValue())).toList()) + "]");
    System.out.println("```");
  }

  private void printDuration(String type, DurationStats stats) {
    System.out.printf("  %s count=%d avg=%d min=%d max=%d total=%d%n",
        type, stats.count(), stats.averageMillis(), stats.minMillis(), stats.maxMillis(), stats.totalMillis());
  }

  private String quote(String value) {
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }

  @Component
  @Command(name = "path", description = "プロファイルログのパスを表示します")
  public static class PathCommand implements Runnable {
    private final ProfileCommand parent;

    public PathCommand(ProfileCommand parent) {
      this.parent = parent;
    }

    @Override
    public void run() {
      parent.printPath();
    }
  }

  @Component
  @Command(name = "summary", description = "プロファイルログの集計を表示します")
  public static class SummaryCommand implements Runnable {
    private final ProfileCommand parent;

    public SummaryCommand(ProfileCommand parent) {
      this.parent = parent;
    }

    @Override
    public void run() {
      parent.printSummary();
    }
  }

  @Component
  @Command(name = "chart", description = "イベント数の ASCII グラフを表示します")
  public static class ChartCommand implements Runnable {
    private final ProfileCommand parent;

    @Option(names = "--bucket-seconds", description = "集計間隔の秒数")
    long bucketSeconds = 60L;

    @Option(names = "--width", description = "棒グラフの最大幅")
    int width = 40;

    public ChartCommand(ProfileCommand parent) {
      this.parent = parent;
    }

    @Override
    public void run() {
      parent.printChart(Math.max(1L, bucketSeconds), Math.max(1, width));
    }
  }

  @Component
  @Command(name = "mermaid", description = "イベント数の Mermaid グラフを表示します")
  public static class MermaidCommand implements Runnable {
    private final ProfileCommand parent;

    @Option(names = "--limit", description = "表示するイベント種別数")
    int limit = 12;

    public MermaidCommand(ProfileCommand parent) {
      this.parent = parent;
    }

    @Override
    public void run() {
      parent.printMermaid(Math.max(1, limit));
    }
  }
}
