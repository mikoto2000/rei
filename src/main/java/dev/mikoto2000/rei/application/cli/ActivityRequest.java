package dev.mikoto2000.rei.application.cli;

import java.util.*;
import picocli.CommandLine;
import picocli.CommandLine.*;

/**
 * Pure grammar matching ActivityCommand; no service, filesystem, screen or execution dependencies.
 */
public record ActivityRequest(
    String operation, String action, String date, boolean verbose, int top, Settings settings) {
  public record Settings(
      Set<String> categories,
      double targetShare,
      int minObservedMinutes,
      double minCoverage,
      double maxUnknownShare,
      int cooldownDays) {
    public Settings {
      categories = Set.copyOf(categories);
    }
  }

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
    @Option(names = "--verbose")
    boolean verbose;

    @Parameters(index = "0", arity = "0..1", defaultValue = "today")
    String action;
  }

  public static class Date {
    @Parameters(index = "0", arity = "0..1", defaultValue = "today")
    String date;
  }

  @Command(name = "summary")
  public static class Summary extends Date {}

  @Command(name = "weekly")
  public static class Weekly extends Date {}

  @Command(name = "monthly")
  public static class Monthly extends Date {}

  @Command(name = "context")
  public static class Context extends Date {}

  @Command(name = "coaching")
  public static class Coaching {
    @Parameters(index = "0", arity = "0..1", defaultValue = "status")
    String action;

    @Parameters(index = "1", arity = "0..1")
    String date;

    @Option(
        names = "--categories",
        split = ",",
        defaultValue = "development,research,documentation")
    Set<String> categories;

    @Option(names = "--target-share", defaultValue = "0.6")
    double targetShare;

    @Option(names = "--min-observed-minutes", defaultValue = "120")
    int observedMinutes;

    @Option(names = "--min-coverage", defaultValue = "0.1")
    double coverage;

    @Option(names = "--max-unknown-share", defaultValue = "0.25")
    double unknownShare;

    @Option(names = "--cooldown-days", defaultValue = "7")
    int cooldownDays;
  }

  public static class Operational {
    @Parameters(index = "0", arity = "0..1", defaultValue = "status")
    String action;

    @Option(names = "--top", defaultValue = "20")
    int top;
  }

  @Command(name = "behavior")
  public static class Behavior extends Operational {}

  @Command(name = "classification")
  public static class Classification extends Operational {}

  public static ActivityRequest parse(List<String> arguments) {
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
