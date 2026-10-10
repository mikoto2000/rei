package dev.mikoto2000.rei.externalagent;

import java.util.*;
import picocli.CommandLine;
import picocli.CommandLine.*;

/** Explicit local review grammar; all paths remain data until checked against the captured Project. */
public record BeginnerReviewRequest(String root, String entry, List<String> order, String audience,
    Set<String> prerequisites, String os, String shell, Mode mode, Format format) {
  public enum Mode { STATIC, LLM }
  public enum Format { TEXT, JSON }
  @Command(name="material-review")
  static final class Options {
    @Parameters(index="0") String perspective;
    @Option(names="--root", defaultValue=".") String root;
    @Option(names="--entry", required=true) String entry;
    @Option(names="--chapter") List<String> chapters = new ArrayList<>();
    @Option(names="--audience", defaultValue="初学者") String audience;
    @Option(names="--prerequisite") List<String> prerequisites = new ArrayList<>();
    @Option(names="--os", defaultValue="未指定") String os;
    @Option(names="--shell", defaultValue="未指定") String shell;
    @Option(names="--mode", defaultValue="STATIC") Mode mode;
    @Option(names="--format", defaultValue="TEXT") Format format;
  }
  public static BeginnerReviewRequest parse(String[] arguments) {
    if (arguments == null || arguments.length > 2048 || Arrays.stream(arguments).mapToInt(String::length).sum() > 32768)
      throw new IllegalArgumentException("Material review input exceeds limit");
    var options = new Options();
    try { new CommandLine(options).setCaseInsensitiveEnumValuesAllowed(true).parseArgs(arguments); }
    catch (CommandLine.ParameterException error) { throw new IllegalArgumentException("Usage: /material-review beginner --root DIR --entry PAGE [--chapter PAGE] [--audience TEXT] [--prerequisite CONCEPT] [--os OS] [--shell SHELL] [--mode static|llm] [--format text|json]"); }
    if (!"beginner".equals(options.perspective) || options.root.isBlank() || options.entry.isBlank())
      throw new IllegalArgumentException("Beginner perspective, material root and entry required");
    return new BeginnerReviewRequest(options.root, options.entry, List.copyOf(options.chapters), options.audience,
        Set.copyOf(options.prerequisites), options.os, options.shell, options.mode, options.format);
  }
  public static boolean accepts(String text) { return text != null && (text.equals("/material-review") || text.startsWith("/material-review ")); }
  public static BeginnerReviewRequest parseText(String text) {
    if (!accepts(text)) throw new IllegalArgumentException("Material review command required");
    var words = new dev.mikoto2000.rei.core.command.UserInputParser().split(text);
    return parse(Arrays.copyOfRange(words, 1, words.length));
  }
}
