package dev.mikoto2000.rei.architecture;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

/** Exact class-pair budget. Normal runs are read-only; shrinking can never accept new debt. */
final class ArchitectureBaseline {
  static void check(Path file, SortedSet<String> actual, boolean shrink) throws IOException {
    if (!Files.isRegularFile(file)) throw new AssertionError("Missing baseline: " + file);
    SortedSet<String> expected = new TreeSet<>(Files.readAllLines(file, StandardCharsets.UTF_8));
    Set<String> added = new TreeSet<>(actual);
    added.removeAll(expected);
    if (!added.isEmpty())
      throw new AssertionError(file + " NEW dependencies:\n" + String.join("\n", added));
    Set<String> removed = new TreeSet<>(expected);
    removed.removeAll(actual);
    if (!removed.isEmpty()) {
      if (shrink) Files.write(file, actual, StandardCharsets.UTF_8);
      else
        throw new AssertionError(
            file + " resolved debt: shrink baseline explicitly:\n" + String.join("\n", removed));
    }
  }
}
