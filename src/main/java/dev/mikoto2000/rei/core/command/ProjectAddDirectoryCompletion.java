package dev.mikoto2000.rei.core.command;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

public final class ProjectAddDirectoryCompletion {

  private static final String PREFIX = "/project add";

  private ProjectAddDirectoryCompletion() {
  }

  public static List<String> complete(String rawLine, Path currentProject) {
    if (rawLine == null || currentProject == null || !rawLine.startsWith(PREFIX)) {
      return List.of();
    }

    String argument = rawLine.substring(PREFIX.length()).stripLeading();
    Quote quote = Quote.from(argument);
    String fragment = quote.stripOpening(argument);

    CompletionTarget target;
    try {
      target = CompletionTarget.from(fragment, currentProject);
    } catch (InvalidPathException e) {
      return List.of();
    }

    if (target.base() == null || !Files.isDirectory(target.base())) {
      return List.of();
    }

    String normalizedPrefix = target.childPrefix().toLowerCase(Locale.ROOT);
    try (var stream = Files.list(target.base())) {
      return stream
          .filter(Files::isDirectory)
          .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).startsWith(normalizedPrefix))
          .map(Path::toString)
          .sorted()
          .map(quote::apply)
          .toList();
    } catch (Exception e) {
      return List.of();
    }
  }

  private static boolean endsWithSeparator(String value) {
    return value.endsWith("/") || value.endsWith("\\");
  }

  private enum Quote {
    NONE(""),
    SINGLE("'"),
    DOUBLE("\"");

    private final String mark;

    Quote(String mark) {
      this.mark = mark;
    }

    static Quote from(String value) {
      if (value.startsWith("\"")) {
        return DOUBLE;
      }
      if (value.startsWith("'")) {
        return SINGLE;
      }
      return NONE;
    }

    String stripOpening(String value) {
      if (this == NONE || value.isEmpty()) {
        return value;
      }
      return value.substring(1);
    }

    String apply(String value) {
      if (this == NONE) {
        return value;
      }
      return mark + value + mark;
    }
  }

  private record CompletionTarget(Path base, String childPrefix) {
    static CompletionTarget from(String fragment, Path currentProject) {
      if (fragment.isBlank()) {
        return new CompletionTarget(currentProject, "");
      }

      Path fragmentPath = Path.of(fragment);
      if (endsWithSeparator(fragment)) {
        return new CompletionTarget(resolve(fragmentPath, currentProject), "");
      }

      Path parent = fragmentPath.getParent();
      String childPrefix = fragmentPath.getFileName() == null ? "" : fragmentPath.getFileName().toString();
      if (parent == null) {
        return new CompletionTarget(currentProject, childPrefix);
      }
      return new CompletionTarget(resolve(parent, currentProject), childPrefix);
    }

    private static Path resolve(Path path, Path currentProject) {
      if (path.isAbsolute()) {
        return path;
      }
      return currentProject.resolve(path).normalize();
    }
  }
}
