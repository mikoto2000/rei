package dev.mikoto2000.rei.cli;

import dev.mikoto2000.rei.application.cli.CliCatalog;
import java.util.*;

/** Pure completion/help over a captured grammar snapshot. No HTTP or service dependencies. */
final class CachedCatalog {
  private final CliCatalog catalog;

  CachedCatalog(CliCatalog catalog) {
    this.catalog = catalog;
  }

  dev.mikoto2000.rei.core.completion.CompletionCandidate presentation(
      List<String> words, int wordIndex, String value) {
    CliCatalog.Command command = null;
    if (wordIndex == 0) command = find(catalog.commands(), value.replaceFirst("^/", ""));
    else {
      var context = target(words, wordIndex);
      if (context.command() != null
          && context.argument() == 0
          && context.pending() == null
          && context.options()) command = find(context.command().commands(), value);
    }
    String kind =
        wordIndex == 0 || command != null ? "command" : value.startsWith("-") ? "option" : "value";
    return new dev.mikoto2000.rei.core.completion.CompletionCandidate(
        value,
        value,
        command == null ? null : command.description().replace('\n', ' '),
        kind,
        true);
  }

  String requestedHelp(List<String> words) {
    if (words.size() < 2
        || words.stream()
            .noneMatch(word -> Set.of("-h", "--help", "-V", "--version").contains(word)))
      return null;
    var root = picocli.CommandLine.Model.CommandSpec.create().name("");
    for (var command : catalog.commands())
      root.addSubcommand(
          command.name(),
          new picocli.CommandLine(dev.mikoto2000.rei.application.cli.CliGrammar.describe(command)));
    var line = new picocli.CommandLine(root);
    markHelp(line);
    var arguments = new ArrayList<>(words);
    arguments.set(0, arguments.getFirst().replaceFirst("^/", ""));
    try {
      var parsed = line.parseArgs(arguments.toArray(String[]::new));
      var path = new ArrayList<String>();
      while (parsed.subcommand() != null) {
        parsed = parsed.subcommand();
        path.add(parsed.commandSpec().name());
      }
      if (parsed.isUsageHelpRequested()) return help(path);
      return parsed.isVersionHelpRequested() ? "" : null;
    } catch (picocli.CommandLine.ParameterException invalid) {
      throw new IllegalArgumentException(invalid.getMessage());
    }
  }

  private static void markHelp(picocli.CommandLine line) {
    for (var option : new ArrayList<>(line.getCommandSpec().options())) {
      boolean help = java.util.Arrays.asList(option.names()).contains("--help"),
          version = java.util.Arrays.asList(option.names()).contains("--version");
      if (help || version) {
        line.getCommandSpec().remove(option);
        line.getCommandSpec()
            .addOption(option.toBuilder().usageHelp(help).versionHelp(version).build());
      }
    }
    new java.util.HashSet<>(line.getSubcommands().values()).forEach(CachedCatalog::markHelp);
  }

  List<String> normalize(List<String> words) {
    if (words.isEmpty() || words.contains("--")) return words;
    var command = find(catalog.commands(), words.getFirst().replaceFirst("^/", ""));
    if (command == null || !command.commands().isEmpty() || command.options().isEmpty())
      return words;
    var line =
        new picocli.CommandLine(dev.mikoto2000.rei.application.cli.CliGrammar.describe(command));
    try {
      var parsed = line.parseArgs(words.subList(1, words.size()).toArray(String[]::new));
      var normalized = new ArrayList<String>();
      normalized.add("/" + command.name());
      for (var parameter : line.getCommandSpec().positionalParameters())
        if (parsed.hasMatchedPositional(parameter.index().min()))
          normalized.addAll(parameter.originalStringValues());
      for (var option : new LinkedHashSet<>(parsed.matchedOptions())) {
        if (option.arity().max() == 0) {
          normalized.add(option.names()[0]);
          continue;
        }
        if (option.isMultiValue() && option.arity().max() == 1)
          for (String value : option.originalStringValues()) {
            normalized.add(option.names()[0]);
            normalized.add(value);
          }
        else {
          normalized.add(option.names()[0]);
          normalized.addAll(option.originalStringValues());
        }
      }
      return List.copyOf(normalized);
    } catch (picocli.CommandLine.ParameterException invalid) {
      throw new IllegalArgumentException(invalid.getMessage());
    }
  }

  List<String> complete(List<String> words, int wordIndex, String prefix) {
    var values = new LinkedHashSet<String>();
    if (wordIndex == 0) {
      for (var command : catalog.commands()) {
        values.add("/" + command.name());
        for (String alias : command.aliases()) values.add("/" + alias);
      }
      return filtered(values, prefix);
    }
    if (words.isEmpty()) return List.of();
    var selected = find(catalog.commands(), words.getFirst().replaceFirst("^/", ""));
    if (selected == null) return List.of();
    var target = target(words, wordIndex);
    selected = target.command();
    if (target.pending() != null)
      return filtered(new LinkedHashSet<>(target.pending().choices()), prefix);
    if (target.options() && prefix.startsWith("-")) {
      int equals = prefix.indexOf('=');
      if (equals > 0) {
        String name = prefix.substring(0, equals);
        for (var option : selected.options())
          if (option.names().contains(name)) {
            for (String choice : option.choices()) values.add(name + "=" + choice);
            return filtered(values, prefix);
          }
      }
      for (var option : selected.options()) values.addAll(option.names());
      return filtered(values, prefix);
    }
    if (target.argument() == 0 && target.options())
      for (var child : selected.commands()) {
        values.add(child.name());
        values.addAll(child.aliases());
      }
    for (var parameter : selected.parameters())
      if (matches(parameter.index(), target.argument())) values.addAll(parameter.choices());
    return filtered(values, prefix);
  }

  boolean requiresSubcommand(List<String> words) {
    var target = target(words, words.size());
    return target.command() != null
        && !target.command().hasDefaultAction()
        && target.argument() == 0
        && target.pending() == null;
  }

  private record Target(
      CliCatalog.Command command, int argument, CliCatalog.Option pending, boolean options) {}

  private Target target(List<String> words, int wordIndex) {
    if (words.isEmpty()) return new Target(null, 0, null, true);
    var selected = find(catalog.commands(), words.getFirst().replaceFirst("^/", ""));
    int argument = 0;
    boolean options = true;
    CliCatalog.Option pending = null;
    for (int index = 1; selected != null && index < wordIndex && index < words.size(); index++) {
      String word = words.get(index);
      if (pending != null) {
        pending = null;
        continue;
      }
      if (options && word.equals("--")) {
        options = false;
        continue;
      }
      if (options && word.startsWith("-")) {
        var name = word.split("=", 2)[0];
        var option =
            selected.options().stream()
                .filter(item -> item.names().contains(name))
                .findFirst()
                .orElse(null);
        if (option != null
            && !option.arity().equals("0")
            && !option.arity().startsWith("0..")
            && !word.contains("=")) pending = option;
        continue;
      }
      var child = argument == 0 && options ? find(selected.commands(), word) : null;
      if (child != null) selected = child;
      else argument++;
    }
    return new Target(selected, argument, pending, options);
  }

  String candidateType(List<String> words, int wordIndex) {
    var target = target(words, wordIndex);
    if (target.command() == null) return "";
    if (target.pending() != null) return target.pending().candidateType();
    return target.command().parameters().stream()
        .filter(parameter -> matches(parameter.index(), target.argument()))
        .map(CliCatalog.Parameter::candidateType)
        .findFirst()
        .orElse("");
  }

  Set<String> pathTypes(List<String> words, int wordIndex, String prefix) {
    if (wordIndex == 0
        && prefix.startsWith("/")
        && prefix.indexOf('/', 1) < 0
        && !prefix.contains("\\")) return Set.of();
    String type = candidateType(words, wordIndex);
    if (type.endsWith("$Directory") || type.endsWith("$ProjectDirectoryCandidates"))
      return Set.of("directory");
    if (type.endsWith("PathCompletionCandidates$File")) return Set.of("file");
    if (type.endsWith("$Any") || type.equals("java.nio.file.Path") || type.equals("java.io.File"))
      return Set.of("file-or-directory");
    if (words.size() > 2
        && words.getFirst().equals("/agent")
        && wordIndex >= 3
        && Set.of("review", "implement", "implementation", "merge", "material-review")
            .contains(words.get(2))) return Set.of("file-or-directory");
    if (type.isEmpty()
        && (prefix.startsWith("./")
            || prefix.startsWith("../")
            || prefix.startsWith("~/")
            || prefix.startsWith("/")
            || prefix.startsWith(".\\")
            || prefix.startsWith("..\\")
            || prefix.startsWith("~\\")
            || prefix.matches("^[a-zA-Z]:[\\\\/].*")
            || prefix.startsWith("\\\\"))) return Set.of("file-or-directory");
    return Set.of();
  }

  String help(List<String> path) {
    List<CliCatalog.Command> commands = catalog.commands();
    CliCatalog.Command selected = null;
    for (String name : path) {
      selected = find(commands, name.replaceFirst("^/", ""));
      if (selected == null) return "";
      commands = selected.commands();
    }
    var text = new StringBuilder();
    if (selected == null) {
      for (var command : commands)
        text.append('/')
            .append(command.name())
            .append("  ")
            .append(command.description())
            .append('\n');
      return text.toString();
    }
    var root = picocli.CommandLine.Model.CommandSpec.create().name("");
    for (var command : catalog.commands())
      root.addSubcommand(
          command.name(),
          new picocli.CommandLine(dev.mikoto2000.rei.application.cli.CliGrammar.describe(command)));
    var line = new picocli.CommandLine(root);
    for (String name : path) line = line.getSubcommands().get(name.replaceFirst("^/", ""));
    text.append(line.getUsageMessage(picocli.CommandLine.Help.Ansi.OFF));
    if (path.getFirst().replaceFirst("^/", "").equals("voice"))
      text.append("Voice is unavailable in this CLI. Use the existing Shell.\n");
    return text.toString();
  }

  private static boolean matches(String index, int position) {
    try {
      String[] bounds = index.split("\\.\\.");
      int first = Integer.parseInt(bounds[0]);
      return position >= first
          && (bounds.length == 1
              ? position == first
              : bounds[1].equals("*") || position <= Integer.parseInt(bounds[1]));
    } catch (NumberFormatException malformed) {
      return position == 0;
    }
  }

  private static CliCatalog.Command find(List<CliCatalog.Command> commands, String name) {
    return commands.stream()
        .filter(command -> command.name().equals(name) || command.aliases().contains(name))
        .findFirst()
        .orElse(null);
  }

  private static List<String> filtered(Set<String> values, String prefix) {
    return values.stream().filter(value -> value.startsWith(prefix)).toList();
  }
}
