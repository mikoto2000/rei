package dev.mikoto2000.rei.ui.shell;

import java.nio.file.Path;
import java.util.*;
import org.jline.reader.ParsedLine;
import picocli.CommandLine;
import picocli.CommandLine.Model.*;
import dev.mikoto2000.rei.core.command.UserInputService;
import dev.mikoto2000.rei.core.completion.*;

/** Reads the live command model without parsing/executing commands or rebinding @Spec. */
public final class CommandCompletionContextResolver {
  public CompletionContext resolve(CommandLine root, ParsedLine line, Path directory) {
    String prefix = line.word().substring(0, Math.min(line.wordCursor(), line.word().length()));
    var path = new ArrayList<String>();
    var types = new LinkedHashSet<String>();
    var choices = new ArrayList<CompletionCandidate>();
    int argument = 0;
    var words = line.words();
    if (line.wordIndex() == 0) {
      // /usr/bin is a path, while /pro is the slash-command namespace.
      if (prefix.startsWith("/") && prefix.indexOf('/', 1) < 0 && !prefix.contains("\\")) {
        types.add("choices");
        root.getSubcommands().forEach((name, cmd) -> choices.add(command("/" + name, cmd)));
        UserInputService.builtins().keySet().stream().sorted()
            .forEach(name -> choices.add(CompletionCandidate.value("/" + name, "command")));
      }
      return context(line, prefix, path, argument, types, choices, directory);
    }
    String first = words.getFirst();
    CommandLine selected = first.startsWith("/") ? root.getSubcommands().get(first.substring(1)) : null;
    if (selected == null) return context(line, prefix, path, argument, types, choices, directory);
    path.add(first.substring(1));
    ArgSpec pending = null;
    int remaining = 0;
    boolean options = true;
    for (int i = 1; i < line.wordIndex(); i++) {
      String word = words.get(i);
      if (remaining > 0) { if (--remaining == 0) pending = null; continue; }
      if (options && word.equals("--")) { options = false; continue; }
      if (options && word.startsWith("-")) {
        String name = word.contains("=") ? word.substring(0, word.indexOf('=')) : word;
        var option = selected.getCommandSpec().optionsMap().get(name);
        if (option != null && !word.contains("=") && option.arity().min() > 0) {
          pending = option; remaining = option.arity().min();
        }
        continue;
      }
      var child = argument == 0 && options ? selected.getSubcommands().get(word) : null;
      if (child != null) { selected = child; path.add(word); }
      else argument++;
    }
    if (pending == null && options && prefix.startsWith("-")) {
      types.add("choices");
      selected.getCommandSpec().optionsMap().forEach((name, option) -> {
        if (!option.hidden()) choices.add(CompletionCandidate.value(name, "option"));
      });
      return context(line, prefix, path, argument, types, choices, directory);
    }
    if (pending == null && argument == 0 && options && !selected.getSubcommands().isEmpty()) {
      types.add("choices");
      selected.getSubcommands().forEach((name, cmd) -> choices.add(command(name, cmd)));
    }
    ArgSpec parameter = pending;
    if (parameter == null) {
      for (var positional : selected.getCommandSpec().positionalParameters()) {
        if (positional.index().contains(argument)) { parameter = positional; break; }
      }
    }
    if (parameter != null) {
      var context = context(line, prefix, path, argument, types, choices, directory);
      var candidates = parameter.completionCandidates();
      if (candidates instanceof CompletionMetadata metadata) {
        types.addAll(metadata.types(context));
        choices.addAll(metadata.choices(context));
      } else if (candidates != null) {
        types.add("choices");
        for (String value : candidates) choices.add(CompletionCandidate.value(value, "value"));
      } else if (parameter.type() == Path.class || parameter.type() == java.io.File.class) {
        types.add(FilePathCompletionProvider.BOTH);
      }
    }
    return context(line, prefix, path, argument, types, choices, directory);
  }
  private CompletionCandidate command(String name, CommandLine command) {
    return new CompletionCandidate(name, name, String.join(" ", command.getCommandSpec().usageMessage().description()),
        "command", true);
  }
  private CompletionContext context(ParsedLine line, String prefix, List<String> path, int argument,
      Set<String> types, List<CompletionCandidate> choices, Path directory) {
    return new CompletionContext(line.line(), line.cursor(), line.words(), line.wordIndex(), argument,
        prefix, path, types, choices, directory);
  }
}
