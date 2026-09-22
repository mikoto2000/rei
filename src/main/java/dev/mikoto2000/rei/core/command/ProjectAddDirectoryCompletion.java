package dev.mikoto2000.rei.core.command;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import dev.mikoto2000.rei.core.completion.*;

/** Compatibility API; the Shell now uses command metadata and FilePathCompletionProvider. */
@Deprecated
public final class ProjectAddDirectoryCompletion {
  private ProjectAddDirectoryCompletion() { }

  public static List<String> complete(String rawLine, Path currentProject) {
    if (rawLine == null || currentProject == null) return List.of();
    String[] words = new UserInputParser().split(rawLine);
    if (words.length < 2 || words.length > 3 || !words[0].equals("/project") || !words[1].equals("add")) return List.of();
    String token = words.length == 3 ? words[2] : "";
    String rawArgument = rawLine.substring(rawLine.indexOf("add") + 3).stripLeading();
    String quote = rawArgument.startsWith("\"") ? "\"" : rawArgument.startsWith("'") ? "'" : "";
    var context = new CompletionContext(rawLine, rawLine.length(), List.of(words), words.length - 1, 0,
        token, List.of("project", "add"), Set.of("directory"), List.of(), currentProject);
    return new FilePathCompletionProvider().complete(context).stream().map(candidate -> {
      Path path = Path.of(candidate.value());
      return quote + (path.isAbsolute() ? path : currentProject.resolve(path)).normalize() + quote;
    }).toList();
  }
}
