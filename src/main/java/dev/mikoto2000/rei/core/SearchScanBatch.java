package dev.mikoto2000.rei.core;

import dev.mikoto2000.rei.tooldelivery.ReadingMetrics;
import dev.mikoto2000.rei.tooldelivery.ReadingProblem;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** File-major query evaluation. Snapshots bound both source and decoded request memory. */
final class SearchScanBatch {
  record Result(Tools.GrepScanResult scan, String error) {}

  private static final class Work {
    final Tools.GrepQuery query;
    final Pattern pattern;
    final Set<String> candidates;
    final int max;
    final List<Tools.GrepMatch> matches = new ArrayList<>();
    final List<Tools.GrepFileError> errors = new ArrayList<>();
    boolean truncated;
    String error;

    Work(Tools.GrepQuery query, Pattern pattern, List<String> candidates) {
      this.query = query;
      this.pattern = pattern;
      this.candidates = Set.copyOf(candidates);
      max =
          query.maxMatches() == null || query.maxMatches() <= 0
              ? Tools.MAX_GREP_MATCHES_PER_QUERY
              : Math.min(Tools.MAX_GREP_MATCHES_PER_QUERY, query.maxMatches());
    }

    boolean matches(String text) {
      return BoundedRegex.find(pattern, text) != Boolean.TRUE.equals(query.invertMatch());
    }
  }

  static List<Result> scan(
      Tools tools,
      List<Tools.GrepQuery> queries,
      Path root,
      FileSnapshots snapshots,
      boolean forSearch)
      throws InterruptedException {
    var work = new ArrayList<Work>();
    var errors = new HashMap<Integer, String>();
    var paths = new TreeSet<String>();
    for (int q = 0; q < queries.size(); q++) {
      var query = queries.get(q);
      try {
        if (query.pattern() == null
            || query.pattern().isBlank()
            || query.baseDir() == null
            || query.baseDir().isBlank())
          throw new IllegalArgumentException("pattern and baseDir must not be blank");
        var pattern =
            tools.compileGrepPattern(
                query.pattern(),
                Boolean.TRUE.equals(query.ignoreCase()),
                Boolean.TRUE.equals(query.fixedString()));
        var include = SearchGlob.compile(query.includeGlob());
        var exclude = SearchGlob.compile(query.excludeGlob());
        Path base = root.resolve(query.baseDir().replace('\\', '/')).toAbsolutePath().normalize();
        if (Files.isRegularFile(base)) base = base.getParent();
        final Path globBase = base;
        var candidates =
            snapshots.inventory(
                List.of(
                    query.baseDir(),
                    Objects.toString(query.includeGlob(), ""),
                    Objects.toString(query.excludeGlob(), "")),
                () ->
                    snapshots
                        .baseInventory(
                            root.resolve(query.baseDir()).toAbsolutePath().normalize().toString(),
                            () -> tools.listFile(query.baseDir(), root))
                        .stream()
                        .filter(path -> tools.matchesGlob(path, include, exclude, root, globBase))
                        .toList());
        paths.addAll(candidates);
        work.add(new Work(query, pattern, candidates));
      } catch (IOException | IllegalArgumentException error) {
        errors.put(q, error.getMessage());
        work.add(null);
      }
    }
    for (String path : paths) {
      Tools.checkReadingCancellation(null);
      var selected =
          work.stream()
              .filter(Objects::nonNull)
              .filter(w -> w.error == null && w.candidates.contains(path))
              .filter(w -> forSearch || w.matches.size() < w.max)
              .toList();
      if (selected.isEmpty()) continue;
      ReadingMetrics.add("scannedFiles", 1);
      FileSnapshots.Snapshot snapshot;
      try {
        snapshot = snapshots.get(root, Path.of(path));
      } catch (IOException error) {
        for (var w : selected)
          w.errors.add(new Tools.GrepFileError(path, "Unable to read file: " + error.getMessage()));
        continue;
      }
      var groups = new java.util.LinkedHashMap<String, List<Work>>();
      for (var w : selected)
        groups
            .computeIfAbsent(Objects.toString(w.query.charset(), ""), unused -> new ArrayList<>())
            .add(w);
      for (var group : groups.entrySet()) {
        List<String> lines;
        try {
          lines =
              snapshots.lines(
                  snapshot, group.getKey(), () -> tools.snapshotLines(snapshot, group.getKey()));
        } catch (IOException | IllegalArgumentException error) {
          for (var w : group.getValue())
            w.errors.add(
                new Tools.GrepFileError(path, "Unable to read file: " + error.getMessage()));
          continue;
        }
        var ranges = new HashMap<Work, LinkedHashSet<Integer>>();
        var done = new java.util.HashSet<Work>();
        for (var w : group.getValue()) ranges.put(w, new LinkedHashSet<>());
        for (int line = 0; line < lines.size() && done.size() < group.getValue().size(); line++) {
          if ((line & 31) == 0) Tools.checkReadingCancellation(null);
          for (var w : group.getValue()) {
            if (done.contains(w)) continue;
            try {
              if (!w.matches(lines.get(line))) continue;
            } catch (ReadingProblem.Failure exhausted) {
              w.error = exhausted.code;
              done.add(w);
              continue;
            }
            var range = ranges.get(w);
            if (forSearch || Boolean.TRUE.equals(w.query.fileNamesOnly())) {
              range.add(line);
              if (Boolean.TRUE.equals(w.query.fileNamesOnly())
                  || range.size() >= Math.min(4, w.max)) {
                done.add(w);
                if (forSearch && !Boolean.TRUE.equals(w.query.fileNamesOnly()))
                  w.truncated |= line + 1 < lines.size();
              }
            } else {
              int before =
                  Math.min(
                      1000,
                      Math.max(0, w.query.beforeContext() == null ? 0 : w.query.beforeContext()));
              int after =
                  Math.min(
                      1000,
                      Math.max(0, w.query.afterContext() == null ? 0 : w.query.afterContext()));
              for (int i = Math.max(0, line - before);
                  i <= Math.min(lines.size() - 1, line + after);
                  i++) {
                range.add(i);
                if (range.size() >= w.max) {
                  done.add(w);
                  break;
                }
              }
            }
          }
        }
        for (var w : group.getValue()) {
          if (w.error != null) continue;
          try {
            for (int line : ranges.get(w)) {
              w.matches.add(
                  new Tools.GrepMatch(
                      path,
                      line + 1,
                      lines.get(line),
                      w.matches(lines.get(line)),
                      snapshot.version()));
              if (!forSearch && w.matches.size() >= w.max) {
                w.truncated = true;
                break;
              }
            }
          } catch (ReadingProblem.Failure exhausted) {
            w.error = exhausted.code;
          }
        }
      }
    }
    var results = new ArrayList<Result>();
    for (int q = 0; q < work.size(); q++) {
      var w = work.get(q);
      results.add(
          w == null
              ? new Result(null, errors.get(q))
              : new Result(new Tools.GrepScanResult(w.matches, w.errors, w.truncated), w.error));
    }
    return results;
  }
}
