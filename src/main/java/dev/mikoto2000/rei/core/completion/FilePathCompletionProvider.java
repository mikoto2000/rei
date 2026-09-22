package dev.mikoto2000.rei.core.completion;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public final class FilePathCompletionProvider implements CompletionProvider {
  public static final String FILE = "file", DIRECTORY = "directory", BOTH = "file-or-directory";
  public record Entry(String name, boolean directory) { }
  @FunctionalInterface public interface DirectoryLister { List<Entry> list(Path parent) throws IOException; }
  private final DirectoryLister directories;
  private final Path home;
  private final boolean windows;
  public FilePathCompletionProvider() {
    this(FilePathCompletionProvider::listDirectory, Path.of(System.getProperty("user.home")),
        java.io.File.separatorChar == '\\');
  }
  public FilePathCompletionProvider(DirectoryLister directories, Path home, boolean windows) {
    this.directories = directories; this.home = home; this.windows = windows;
  }
  public static List<Entry> listDirectory(Path parent) throws IOException {
    try (var children = Files.list(parent)) {
      return children.map(path -> new Entry(path.getFileName().toString(), Files.isDirectory(path))).toList();
    }
  }
  @Override public boolean supports(CompletionContext context) {
    return context.types().stream().anyMatch(type -> Set.of(FILE, DIRECTORY, BOTH).contains(type));
  }
  @Override public List<CompletionCandidate> complete(CompletionContext context) {
    try {
      String token = PathFragment.expandHome(context.currentToken(), home, windows);
      var fragment = PathFragment.parse(token, windows);
      // Drive-relative resolution depends on process state, so only complete rooted drive paths.
      if (windows && fragment.parent().matches("^[a-zA-Z]:$")) return List.of();
      Path parent = fragment.parent().isEmpty() ? context.workingDirectory() : Path.of(fragment.parent());
      if (!parent.isAbsolute()) parent = context.workingDirectory().resolve(parent);
      boolean files = context.types().contains(FILE) || context.types().contains(BOTH);
      boolean dirs = context.types().contains(DIRECTORY) || context.types().contains(BOTH);
      return directories.list(parent).stream()
          .filter(entry -> entry.directory() ? dirs : files)
          .filter(entry -> startsWith(entry.name(), fragment.prefix()))
          .sorted(Comparator.comparing(Entry::name))
          .map(entry -> {
            String value = fragment.parent() + entry.name() + (entry.directory() ? fragment.separator() : "");
            return new CompletionCandidate(value, value, null, entry.directory() ? DIRECTORY : FILE, !entry.directory());
          }).toList();
    } catch (IOException | RuntimeException ignored) { return List.of(); }
  }
  private boolean startsWith(String value, String prefix) {
    return windows ? value.regionMatches(true, 0, prefix, 0, prefix.length()) : value.startsWith(prefix);
  }
}
