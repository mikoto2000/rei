package dev.mikoto2000.rei.core;

import dev.mikoto2000.rei.core.chat.AgentRunScope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;

/** Persistent search-result and scan cursors, separate from native file read cursors. */
final class PagedFileSearch {
  public record Request(List<Tools.GrepQuery> queries, Integer contextLines, Integer maxTokens,
      Integer maxBytes, Integer scanLimit, Integer maxLines, String cursor) {}
  public record Progress(int scanned, int unscanned, int total, String stopReason) {}
  public record Page(List<Tools.SearchAndReadResult> files, String resultCursor, String scanCursor,
      Progress progress, ReadingProblem error) {}
  public record Stamp(String path, long size, String modified, String key) {}
  public record Range(int start, int end) {}
  public static final class State {
    public Request request;
    public List<Stamp> inventory = List.of();
    public int file;
    public String version;
    public List<Range> ranges = List.of();
    public int rangeIndex;
    public int offset;
    public int scanLine;
  }
  private final Tools tools;
  private final ReadingStateStore store;
  PagedFileSearch(Tools tools, ReadingStateStore store) { this.tools = tools; this.store = store; }

  Page read(Request input, Path root) {
    try (var measurement = ReadingMetrics.operation("search")) {
      if (input == null) throw ReadingProblem.fail("INVALID_ARGUMENT");
      int tokens = value(input.maxTokens(), 2048, 256, 8000);
      int bytes = value(input.maxBytes(), 16384, 1024, 65536);
      int scanLimit = value(input.scanLimit(), 128, 1, 1024);
      int maxLines = value(input.maxLines(), 1000, 1, 5000);
      var owner = AgentRunScope.current();
      ReadingStateStore.scope(owner);
      if (!root.toRealPath().equals(owner.projectRoot().toRealPath())) throw ReadingProblem.fail("INVALID_CURSOR");
      State state;
      if (input.cursor() == null) {
        validate(input, root);
        state = new State();
        state.request = new Request(input.queries(), value(input.contextLines(), 25, 0, 1000), null, null, null, null, null);
        state.inventory = inventory(input.queries(), root);
      } else {
        if (!input.cursor().matches("(?:RESULT|SCAN):[0-9a-f-]{36}")) throw ReadingProblem.fail("INVALID_CURSOR");
        state = store.load(owner, "search", input.cursor(), State.class);
        if (input.queries() != null && !input.queries().equals(state.request.queries())) throw ReadingProblem.fail("INVALID_CURSOR");
        ReadingMetrics.add("continuations", 1);
        for (var stamp : state.inventory) if (!stamp.equals(stamp(root, stamp.path()))) throw ReadingProblem.fail("FILE_CHANGED");
      }
      int scanned = 0, readBytes = 0;
      while (state.file < state.inventory.size()) {
        dev.mikoto2000.rei.core.chat.RunCancellation.propagate(null);
        var item = state.inventory.get(state.file);
        if (item.size() > FileSnapshots.MAX_FILE_BYTES) {
          state.file++;
          return paused(state, List.of(), "FILE_SIZE_LIMIT", ReadingProblem.from(ReadingProblem.fail("FILE_SIZE_LIMIT")));
        }
        if (scanned >= scanLimit) return paused(state, List.of(), "FILE_ENUMERATION_LIMIT", null);
        if (readBytes + item.size() > FileSnapshots.MAX_REQUEST_BYTES) return paused(state, List.of(), "SNAPSHOT_BUDGET", null);
        var snapshots = tools.newSnapshots();
        var snapshot = snapshots.get(root, Path.of(item.path()));
        readBytes += snapshot.bytes().length;
        if (state.version != null && !state.version.equals(snapshot.version())) throw ReadingProblem.fail("FILE_CHANGED");
        state.version = snapshot.version();
        String charset = state.request.queries().getFirst().charset();
        var lines = snapshots.lines(snapshot, charset, () -> tools.snapshotLines(snapshot, charset));
        if (state.ranges.isEmpty()) {
          ReadingMetrics.add("scannedFiles", 1);
          scanned++;
          scan(state, lines, root, item.path());
        }
        if (!state.ranges.isEmpty()) {
          var sections = new ArrayList<Tools.ReadSection>();
          var matches = new ArrayList<Tools.SearchMatch>();
          int count = 0;
          while (state.rangeIndex < state.ranges.size() && count < maxLines) {
            var range = state.ranges.get(state.rangeIndex);
            int start = range.start();
            int stop = Math.min(range.end(), start + maxLines - count);
            // Select an exact source prefix and fit its serialized envelope before advancing state.
            var budget = new TextReadBudget(Math.max(0, bytes - 1024));
            var slice = budget.take(lines, start, stop, state.offset, maxLines - count);
            if (slice.lines().isEmpty()) break;
            int keep = slice.lines().size();
            Tools.SearchAndReadResult candidate;
            while (true) {
              var pending = new ArrayList<>(sections);
              pending.add(new Tools.ReadSection(start + 1, start + keep, slice.lines().subList(0, keep), true));
              candidate = new Tools.SearchAndReadResult(item.path(), matches, pending, null, true, false, state.version, null);
              if (FileResultBudget.fits(candidate, Math.max(256, bytes - 768), Math.max(128, tokens - 256))) break;
              if (keep <= 1) {
                String text = slice.lines().getFirst();
                int end = text.length();
                while (end > 0 && !FileResultBudget.fits(new Tools.SearchAndReadResult(item.path(), List.of(),
                    List.of(new Tools.ReadSection(start + 1, start + 1, List.of(text.substring(0, end)), true)),
                    null, true, false, state.version, null), Math.max(256, bytes - 768), Math.max(128, tokens - 256))) {
                  end /= 2;
                  if (end > 0 && Character.isLowSurrogate(text.charAt(end))) end--;
                }
                if (end == 0 || !sections.isEmpty()) return paused(state, List.of(result(item.path(), state, sections, matches)), "RESULT_OUTPUT_BUDGET", null);
                sections.add(new Tools.ReadSection(start + 1, start + 1, List.of(text.substring(0, end)), true));
                state.offset += end;
                return paused(state, List.of(result(item.path(), state, sections, matches)), "RESULT_OUTPUT_BUDGET", null);
              }
              keep = Math.max(1, keep / 2);
            }
            sections.add(candidate.sections().getLast());
            count += keep;
            if (keep < slice.lines().size()) {
              state.ranges = replaceRange(state.ranges, state.rangeIndex, new Range(start + keep, range.end()));
              state.offset = 0;
              break;
            }
            if (slice.nextOffset() > 0) {
              state.ranges = replaceRange(state.ranges, state.rangeIndex, new Range(slice.nextLine() - 1, range.end()));
              state.offset = slice.nextOffset();
              break;
            }
            int nextLine = start + keep;
            state.offset = 0;
            if (nextLine < range.end()) {
              state.ranges = replaceRange(state.ranges, state.rangeIndex, new Range(nextLine, range.end()));
              break;
            }
            state.rangeIndex++;
          }
          var result = result(item.path(), state, sections, matches);
          if (state.rangeIndex >= state.ranges.size()) {
            state.ranges = List.of(); state.rangeIndex = 0;
            if (state.scanLine >= lines.size()) advance(state);
          }
          return paused(state, List.of(result), state.file < state.inventory.size() ? "RESULT_OUTPUT_BUDGET" : "COMPLETE", null);
        }
        if (state.scanLine >= lines.size()) advance(state);
      }
      return paused(state, List.of(), "COMPLETE", null);
    } catch (Exception error) {
      dev.mikoto2000.rei.core.chat.RunCancellation.propagate(error);
      return new Page(List.of(), null, null, new Progress(0, 0, 0, "FAILED"), ReadingProblem.from(error));
    }
  }

  private Tools.SearchAndReadResult result(String path, State state, List<Tools.ReadSection> sections, List<Tools.SearchMatch> matches) {
    return new Tools.SearchAndReadResult(path, List.copyOf(matches), List.copyOf(sections), null, true, false, state.version, null);
  }
  private Page paused(State state, List<Tools.SearchAndReadResult> files, String reason, ReadingProblem error) throws IOException {
    String result = null, scan = null;
    if (state.file < state.inventory.size()) {
      boolean pending = state.rangeIndex < state.ranges.size();
      String id = (pending ? "RESULT:" : "SCAN:") + UUID.randomUUID();
      store.save(AgentRunScope.current(), "search", id, state, Duration.ofHours(24));
      if (pending) result = id; else scan = id;
    }
    ReadingMetrics.add("unscannedFiles", state.inventory.size() - state.file);
    ReadingMetrics.add("stop." + reason, 1);
    return ReadingMetrics.generated(new Page(files, result, scan,
        new Progress(state.file, state.inventory.size() - state.file, state.inventory.size(), reason), error));
  }
  private static void advance(State state) {
    state.file++; state.version = null; state.scanLine = 0; state.offset = 0; state.ranges = List.of(); state.rangeIndex = 0;
  }
  private static List<Range> replaceRange(List<Range> original, int index, Range value) {
    var ranges = new ArrayList<>(original); ranges.set(index, value); return List.copyOf(ranges);
  }
  private void scan(State state, List<String> lines, Path root, String path) {
    var applicable = state.request.queries().stream().filter(query -> applies(query, path, root)).toList();
    var patterns = applicable.stream().map(query -> tools.compileGrepPattern(query.pattern(), Boolean.TRUE.equals(query.ignoreCase()), Boolean.TRUE.equals(query.fixedString()))).toList();
    var ranges = new ArrayList<Range>();
    int context = state.request.contextLines();
    int line = state.scanLine;
    for (; line < lines.size(); line++) {
      if ((line & 31) == 0) dev.mikoto2000.rei.core.chat.RunCancellation.propagate(null);
      boolean hit = false;
      for (int q = 0; q < applicable.size(); q++) hit |= patterns.get(q).matcher(lines.get(line)).find() != Boolean.TRUE.equals(applicable.get(q).invertMatch());
      if (!hit) continue;
      int start = Math.max(0, line - context), end = Math.min(lines.size(), line + context + 1);
      if (!ranges.isEmpty() && start <= ranges.getLast().end()) {
        var last = ranges.removeLast(); ranges.add(new Range(last.start(), end));
      } else {
        if (ranges.size() == 256) break;
        ranges.add(new Range(start, end));
      }
    }
    state.scanLine = line;
    state.ranges = List.copyOf(ranges);
  }
  private boolean applies(Tools.GrepQuery query, String path, Path root) {
    Path base = root.resolve(query.baseDir()).normalize();
    Path candidate = root.resolve(path).normalize();
    if (Files.isRegularFile(base)) { if (!candidate.equals(base)) return false; base = base.getParent(); }
    else if (!candidate.startsWith(base)) return false;
    return tools.matchesGlob(path, SearchGlob.compile(query.includeGlob()), SearchGlob.compile(query.excludeGlob()), root, base);
  }
  private List<Stamp> inventory(List<Tools.GrepQuery> queries, Path root) throws IOException, InterruptedException {
    var paths = new TreeSet<String>();
    for (var query : queries) {
      var base = FileSnapshots.resolveInventoryPath(root, Path.of(query.baseDir()));
      var listed = tools.gitLsFiles(List.of(query.baseDir()), root);
      if (listed == null) {
        try (var stream = Files.walk(base, 20)) {
          listed = stream.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
              .map(root::relativize).map(Path::toString).map(path -> path.replace('\\', '/')).limit(20001).toList();
        }
      }
      for (String path : listed) if (applies(query, path, root)) {
        try { FileSnapshots.resolve(root, Path.of(path)); paths.add(path); }
        catch (IOException excluded) { /* Sensitive and linked paths are never enumerated. */ }
      }
      if (paths.size() > 20000) throw ReadingProblem.fail("FILE_ENUMERATION_LIMIT");
    }
    var result = new ArrayList<Stamp>();
    for (String path : paths) result.add(stamp(root, path));
    return List.copyOf(result);
  }
  private static Stamp stamp(Path root, String relative) throws IOException {
    var path = FileSnapshots.resolve(root, Path.of(relative));
    var attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    return new Stamp(relative, attrs.size(), attrs.lastModifiedTime().toString(), Objects.toString(attrs.fileKey(), ""));
  }
  private void validate(Request input, Path root) throws IOException {
    if (input.queries() == null || input.queries().isEmpty() || input.queries().size() > 20) throw ReadingProblem.fail("INVALID_ARGUMENT");
    String charset = input.queries().getFirst().charset();
    for (var query : input.queries()) {
      if (query == null || query.pattern() == null || query.pattern().isBlank() || query.pattern().length() > 1024
          || query.baseDir() == null || !Objects.equals(charset, query.charset())) throw ReadingProblem.fail("INVALID_ARGUMENT");
      FileSnapshots.resolveInventoryPath(root, Path.of(query.baseDir()));
      tools.compileGrepPattern(query.pattern(), Boolean.TRUE.equals(query.ignoreCase()), Boolean.TRUE.equals(query.fixedString()));
    }
  }
  private static int value(Integer value, int fallback, int min, int max) {
    int effective = value == null ? fallback : value;
    if (effective < min || effective > max) throw ReadingProblem.fail("INVALID_ARGUMENT");
    return effective;
  }
}
