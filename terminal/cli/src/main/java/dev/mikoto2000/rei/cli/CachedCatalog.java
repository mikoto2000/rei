package dev.mikoto2000.rei.cli;

import dev.mikoto2000.rei.application.cli.CliCatalog;
import java.util.*;

/**
 * 軽量CLIでの対話に必要な保存済み利用可能な項目を扱う。
 *
 * <p>Pure completion/help over a captured grammar snapshot. No HTTP or service dependencies.
 */
final class CachedCatalog {
  /** 利用可能な項目。 */
  private final CliCatalog catalog;

  /** 指定された値や利用先を使って、CachedCatalogを初期化する。 */
  CachedCatalog(CliCatalog catalog) {
    this.catalog = catalog;
  }

  /** 軽量CLIでの対話で用いるpresentationを返す。 */
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

  /** 軽量CLIでの対話で用いる要求された使い方を返す。 */
  String requestedHelp(List<String> words) {
    if (words.size() < 2
        || words.stream()
            .noneMatch(word -> Set.of("-h", "--help", "-V", "--version").contains(word)))
      return null;
    var line = ClientHelpCatalog.create(catalog);
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

  /** 同じ意味の指定を共通に扱えるよう、表記や形式をそろえる。 */
  List<String> normalize(List<String> words) {
    if (words.isEmpty() || words.contains("--")) return words;
    var command = find(catalog.commands(), words.getFirst().replaceFirst("^/", ""));
    if (command == null
        || !command.commands().isEmpty()
        || command.options().stream()
            .allMatch(
                option ->
                    option.names().contains("--help") || option.names().contains("--version")))
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

  /** 処理の完了を記録し、後続の処理へ結果を引き渡す。 */
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

  /** 軽量CLIでの対話で判断に用いるrequiresSubcommandの条件を確認する。 */
  boolean requiresSubcommand(List<String> words) {
    var target = target(words, words.size());
    return target.command() != null
        && !target.command().hasDefaultAction()
        && target.argument() == 0
        && target.pending() == null;
  }

  /**
   * 軽量CLIでの対話で受け渡す対象を表す。
   *
   * @param command コマンド
   * @param argument 引数
   * @param pending 待機中
   * @param options 選択可能な設定
   */
  private record Target(
      CliCatalog.Command command, int argument, CliCatalog.Option pending, boolean options) {}

  /** 軽量CLIでの対話で用いる対象を返す。 */
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

  /** 軽量CLIでの対話で用いる候補種別を返す。 */
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

  /** 軽量CLIでの対話で用いるパス種別一覧を返す。 */
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
    // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
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

  /** 軽量CLIでの対話で用いる使い方を返す。 */
  String help(List<String> path) {
    var line = ClientHelpCatalog.create(catalog);
    for (String name : path) {
      line = line.getSubcommands().get(name.replaceFirst("^/", ""));
      if (line == null) return "";
    }
    var text = new StringBuilder();
    text.append(line.getUsageMessage(picocli.CommandLine.Help.Ansi.OFF));
    if (!path.isEmpty() && path.getFirst().replaceFirst("^/", "").equals("voice"))
      text.append("Voice is unavailable in this CLI. Use the existing Shell.\n");
    return text.toString();
  }

  /** 指定された条件と対象が一致するかを判定する。 */
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

  /** 対象を特定できるよう、保存済み利用可能な項目を探す。 */
  private static CliCatalog.Command find(List<CliCatalog.Command> commands, String name) {
    return commands.stream()
        .filter(command -> command.name().equals(name) || command.aliases().contains(name))
        .findFirst()
        .orElse(null);
  }

  /** 軽量CLIでの対話で用いる条件に合うを返す。 */
  private static List<String> filtered(Set<String> values, String prefix) {
    return values.stream().filter(value -> value.startsWith(prefix)).toList();
  }
}
