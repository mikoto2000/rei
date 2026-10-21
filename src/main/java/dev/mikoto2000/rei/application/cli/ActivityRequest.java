package dev.mikoto2000.rei.application.cli;

import java.util.List;
import java.util.Set;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * コマンドの案内で受け渡す作業状況要求を表す。
 *
 * <p>Pure grammar matching ActivityCommand; no service, filesystem, screen or execution
 * dependencies.
 *
 * @param operation 操作
 * @param action 操作
 * @param date 日付
 * @param verbose 詳細な形式
 * @param top 上位
 * @param settings 設定
 */
public record ActivityRequest(
    String operation, String action, String date, boolean verbose, int top, Settings settings) {
  /**
   * コマンドの案内で受け渡す設定を表す。
   *
   * @param categories 分類一覧
   * @param targetShare 対象共有
   * @param minObservedMinutes 最小観測分数
   * @param minCoverage 最小対象範囲
   * @param maxUnknownShare 最大不明共有
   * @param cooldownDays 通知を控える期間日数
   */
  public record Settings(
      Set<String> categories,
      double targetShare,
      int minObservedMinutes,
      double minCoverage,
      double maxUnknownShare,
      int cooldownDays) {
    /** 受け取った情報からSettingsを作成する。 */
    public Settings {
      categories = Set.copyOf(categories);
    }
  }

  /** コマンドの案内に必要な選択可能な設定を扱う。 */
  @Command(
      name = "activity",
      subcommands = {
        Summary.class,
        Weekly.class,
        Monthly.class,
        Context.class,
        Coaching.class,
        Behavior.class,
        Classification.class
      })
  public static class Options {
    /** 詳細な形式。 */
    @Option(names = "--verbose")
    boolean verbose;

    /** 操作。 */
    @Parameters(index = "0", arity = "0..1", defaultValue = "today")
    String action;
  }

  /** コマンドの案内に必要な日付を扱う。 */
  public static class Date {
    /** 日付。 */
    @Parameters(index = "0", arity = "0..1", defaultValue = "today")
    String date;
  }

  /** コマンドの案内に必要な要約を扱う。 */
  @Command(name = "summary")
  public static class Summary extends Date {}

  /** コマンドの案内に必要な週次を扱う。 */
  @Command(name = "weekly")
  public static class Weekly extends Date {}

  /** コマンドの案内に必要な月次を扱う。 */
  @Command(name = "monthly")
  public static class Monthly extends Date {}

  /** コマンドの案内に必要な文脈を扱う。 */
  @Command(name = "context")
  public static class Context extends Date {}

  /** コマンドの案内に必要な助言を扱う。 */
  @Command(name = "coaching")
  public static class Coaching {
    /** 操作。 */
    @Parameters(index = "0", arity = "0..1", defaultValue = "status")
    String action;

    /** 日付。 */
    @Parameters(index = "1", arity = "0..1")
    String date;

    /** 分類一覧。 */
    @Option(
        names = "--categories",
        split = ",",
        defaultValue = "development,research,documentation")
    Set<String> categories;

    /** 対象共有。 */
    @Option(names = "--target-share", defaultValue = "0.6")
    double targetShare;

    /** 観測分数。 */
    @Option(names = "--min-observed-minutes", defaultValue = "120")
    int observedMinutes;

    /** 対象範囲。 */
    @Option(names = "--min-coverage", defaultValue = "0.1")
    double coverage;

    /** 不明共有。 */
    @Option(names = "--max-unknown-share", defaultValue = "0.25")
    double unknownShare;

    /** 通知を控える期間日数。 */
    @Option(names = "--cooldown-days", defaultValue = "7")
    int cooldownDays;
  }

  /** コマンドの案内に必要なOperationalを扱う。 */
  public static class Operational {
    /** 操作。 */
    @Parameters(index = "0", arity = "0..1", defaultValue = "status")
    String action;

    /** 上位。 */
    @Option(names = "--top", defaultValue = "20")
    int top;
  }

  /** コマンドの案内に必要な作業習慣を扱う。 */
  @Command(name = "behavior")
  public static class Behavior extends Operational {}

  /** コマンドの案内に必要な分類を扱う。 */
  @Command(name = "classification")
  public static class Classification extends Operational {}

  /** 受け取った入力を解釈し、機能内で扱える情報へ変換する。 */
  public static ActivityRequest parse(List<String> arguments) {
    // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
    if (arguments.size() > 100 || arguments.stream().mapToLong(String::length).sum() > 16384)
      throw new IllegalArgumentException("Activity arguments too large");
    var options = new Options();
    CommandLine.ParseResult parsed;
    try {
      parsed = new CommandLine(options).parseArgs(arguments.toArray(String[]::new));
    } catch (CommandLine.ParameterException invalid) {
      throw new IllegalArgumentException("Invalid Activity arguments", invalid);
    }
    if (!parsed.hasSubcommand())
      return new ActivityRequest(
          "timeline", options.action, options.action, options.verbose, 20, null);
    var child = parsed.subcommand();
    Object value = child.commandSpec().userObject();
    String operation = child.commandSpec().name();
    if (value instanceof Date date)
      return new ActivityRequest(operation, null, date.date, false, 20, null);
    if (value instanceof Coaching coaching) {
      if (coaching.date != null && !Set.of("weekly", "monthly").contains(coaching.action))
        throw new IllegalArgumentException("Date requires weekly/monthly coaching");
      if (!coaching.action.equals("configure")
          && List.of(
                  "--categories",
                  "--target-share",
                  "--min-observed-minutes",
                  "--min-coverage",
                  "--max-unknown-share",
                  "--cooldown-days")
              .stream()
              .anyMatch(child::hasMatchedOption))
        throw new IllegalArgumentException("Criteria require coaching configure");
      return new ActivityRequest(
          operation,
          coaching.action,
          coaching.date,
          false,
          20,
          new Settings(
              coaching.categories,
              coaching.targetShare,
              coaching.observedMinutes,
              coaching.coverage,
              coaching.unknownShare,
              coaching.cooldownDays));
    }
    if (value instanceof Operational operational)
      return new ActivityRequest(operation, operational.action, null, false, operational.top, null);
    throw new IllegalArgumentException("Unknown Activity operation");
  }
}
