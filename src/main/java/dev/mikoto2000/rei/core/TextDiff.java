package dev.mikoto2000.rei.core;

import dev.mikoto2000.rei.toolcontract.CredentialMask;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Shared line diff with bounded Myers search, correct replacement fallback and UTF-8 paging. */
public final class TextDiff {
  private TextDiff() {}

  /** One-based, half-open changed line intervals; an empty interval denotes insertion/deletion. */
  public record Range(int oldStartLine, int oldEndLine, int newStartLine, int newEndLine) {}

  public record FileSummary(
      String path, int changeCount, int addedLines, int removedLines, List<Range> ranges) {
    public FileSummary {
      ranges = List.copyOf(ranges);
    }
  }

  /** Hunk indexes are one-based; the interval includes a hunk partially outside the page. */
  public record Omission(String path, int firstHunk, int lastHunk) {}

  /** nextOffset is an opaque UTF-16 cursor, bound to the exact saved proposal SHA. */
  public record Info(
      int totalBytes,
      int returnedBytes,
      boolean truncated,
      int nextOffset,
      int omittedBytes,
      List<Omission> omissions,
      @com.fasterxml.jackson.annotation.JsonInclude(
              com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
          String retrieval) {
    public Info {
      omissions = List.copyOf(omissions);
    }
  }

  public record Page(String diff, Info info) {}

  private record Row(char kind, String text) {}

  private record Hunk(String path, int index, int start, int end) {}

  private record Rendered(String text, List<Hunk> hunks, List<FileSummary> summaries) {}

  /** Summaries never contain source text. changeCount counts contiguous changed line regions. */
  public static List<FileSummary> summaries(List<TextDocumentTransaction.Change> changes) {
    return render(changes, false).summaries();
  }

  public static Page page(List<TextDocumentTransaction.Change> changes, int offset, int maxBytes) {
    if (maxBytes < 256 || maxBytes > 65536)
      throw new IllegalArgumentException("diff maxBytes must be 256..65536");
    var rendered = render(changes, true);
    String full = rendered.text();
    if (offset < 0
        || offset > full.length()
        || offset > 0
            && offset < full.length()
            && Character.isLowSurrogate(full.charAt(offset))
            && Character.isHighSurrogate(full.charAt(offset - 1)))
      throw new IllegalArgumentException("Use the returned diff cursor unchanged");
    int end = offset, bytes = 0;
    while (end < full.length()) {
      int point = full.codePointAt(end);
      int size = point <= 0x7f ? 1 : point <= 0x7ff ? 2 : point <= 0xffff ? 3 : 4;
      if (bytes + size > maxBytes) break;
      bytes += size;
      end += Character.charCount(point);
    }
    int total = full.getBytes(StandardCharsets.UTF_8).length;
    var omissions = new ArrayList<Omission>();
    for (var change : changes) {
      int first = Integer.MAX_VALUE, last = 0;
      for (var hunk : rendered.hunks()) {
        if (hunk.path().equals(change.path()) && (hunk.start() < offset || hunk.end() > end)) {
          first = Math.min(first, hunk.index());
          last = hunk.index();
        }
      }
      if (last > 0) omissions.add(new Omission(change.path(), first, last));
    }
    boolean truncated = offset > 0 || end < full.length();
    return new Page(
        full.substring(offset, end),
        new Info(
            total,
            bytes,
            truncated,
            end < full.length() ? end : -1,
            total - bytes,
            List.copyOf(omissions),
            truncated
                ? "Use getTextChangeSetDiff or getTextDocumentChangeSetDiff with id, proposalSha256, nextOffset as offset and maxBytes. Inspection can retrieve the preview again. Review all needed pages before Apply."
                : ""));
  }

  private static Rendered render(
      List<TextDocumentTransaction.Change> changes, boolean includeBody) {
    var out = new StringBuilder();
    var hunks = new ArrayList<Hunk>();
    var summaries = new ArrayList<FileSummary>();
    for (var change : changes) {
      List<Row> rows = rows(change.before(), change.after());
      int[] oldPositions = new int[rows.size() + 1], newPositions = new int[rows.size() + 1];
      var ranges = new ArrayList<Range>();
      int added = 0, removed = 0;
      for (int i = 0; i < rows.size(); i++) {
        char kind = rows.get(i).kind();
        oldPositions[i + 1] = oldPositions[i] + (kind == '+' ? 0 : 1);
        newPositions[i + 1] = newPositions[i] + (kind == '-' ? 0 : 1);
        if (kind == '+') added++;
        if (kind == '-') removed++;
      }
      for (int i = 0; i < rows.size(); ) {
        if (rows.get(i).kind() == ' ') {
          i++;
          continue;
        }
        int start = i;
        while (i < rows.size() && rows.get(i).kind() != ' ') i++;
        ranges.add(
            new Range(
                oldPositions[start] + 1,
                oldPositions[i] + 1,
                newPositions[start] + 1,
                newPositions[i] + 1));
      }
      summaries.add(
          new FileSummary(change.path(), ranges.size(), added, removed, List.copyOf(ranges)));
      if (ranges.isEmpty() || !includeBody) continue;
      out.append("--- ").append(change.before() == null ? "/dev/null" : change.path()).append('\n');
      out.append("+++ ").append(change.after() == null ? "/dev/null" : change.path()).append('\n');
      int index = 0;
      for (int i = 0; i < rows.size(); ) {
        while (i < rows.size() && rows.get(i).kind() == ' ') i++;
        if (i == rows.size()) break;
        int from = Math.max(0, i - 3), lastChange = i;
        i++;
        while (i < rows.size()) {
          if (rows.get(i).kind() != ' ') lastChange = i;
          else if (i - lastChange > 6) break;
          i++;
        }
        int to = Math.min(rows.size(), lastChange + 4), start = out.length();
        int oldCount = oldPositions[to] - oldPositions[from];
        int newCount = newPositions[to] - newPositions[from];
        out.append("@@ -")
            .append(oldPositions[from] + (oldCount == 0 ? 0 : 1))
            .append(',')
            .append(oldCount)
            .append(" +")
            .append(newPositions[from] + (newCount == 0 ? 0 : 1))
            .append(',')
            .append(newCount)
            .append(" @@\n");
        for (int j = from; j < to; j++) {
          var row = rows.get(j);
          out.append(row.kind()).append(row.text());
          if (!row.text().endsWith("\n")) out.append("\n\\ No newline at end of file\n");
        }
        hunks.add(new Hunk(change.path(), ++index, start, out.length()));
      }
    }
    // Redact each rendered line while keeping cursor/hunk coordinates coherent.
    String raw = out.toString();
    var safe = new StringBuilder();
    var safeHunks = new ArrayList<Hunk>();
    int cursor = 0;
    for (var hunk : hunks) {
      safe.append(CredentialMask.redact(raw.substring(cursor, hunk.start())));
      int start = safe.length();
      safe.append(CredentialMask.redact(raw.substring(hunk.start(), hunk.end())));
      safeHunks.add(new Hunk(hunk.path(), hunk.index(), start, safe.length()));
      cursor = hunk.end();
    }
    safe.append(CredentialMask.redact(raw.substring(cursor)));
    return new Rendered(safe.toString(), List.copyOf(safeHunks), List.copyOf(summaries));
  }

  private static List<String> lines(String text) {
    var result = new ArrayList<String>();
    if (text == null || text.isEmpty()) return result;
    int start = 0;
    for (int i = 0; i < text.length(); i++)
      if (text.charAt(i) == '\n') {
        result.add(text.substring(start, i + 1));
        start = i + 1;
      }
    if (start < text.length()) result.add(text.substring(start));
    return result;
  }

  private static List<Row> rows(String before, String after) {
    var old = lines(before);
    var next = lines(after);
    int prefix = 0, suffix = 0;
    while (prefix < old.size() && prefix < next.size() && old.get(prefix).equals(next.get(prefix)))
      prefix++;
    while (suffix < old.size() - prefix
        && suffix < next.size() - prefix
        && old.get(old.size() - suffix - 1).equals(next.get(next.size() - suffix - 1))) suffix++;
    var result = new ArrayList<Row>();
    for (int i = 0; i < prefix; i++) result.add(new Row(' ', old.get(i)));
    var a = old.subList(prefix, old.size() - suffix);
    var b = next.subList(prefix, next.size() - suffix);
    result.addAll(myers(a, b));
    for (int i = old.size() - suffix; i < old.size(); i++) result.add(new Row(' ', old.get(i)));
    return result;
  }

  private static List<Row> myers(List<String> a, List<String> b) {
    final int maximum = 256, offset = maximum + 1;
    int[] frontier = new int[2 * maximum + 3];
    Arrays.fill(frontier, -1);
    frontier[offset + 1] = 0;
    var trace = new ArrayList<int[]>();
    int comparisons = 0;
    for (int depth = 0; depth <= maximum; depth++) {
      trace.add(frontier.clone());
      for (int k = -depth; k <= depth; k += 2) {
        int x =
            k == -depth || k != depth && frontier[offset + k - 1] < frontier[offset + k + 1]
                ? frontier[offset + k + 1]
                : frontier[offset + k - 1] + 1;
        int y = x - k;
        while (x < a.size() && y < b.size() && a.get(x).equals(b.get(y))) {
          if (++comparisons > 2000000) return replacement(a, b);
          x++;
          y++;
        }
        frontier[offset + k] = x;
        if (x == a.size() && y == b.size()) return backtrack(a, b, trace, offset);
      }
    }
    return replacement(a, b);
  }

  private static List<Row> backtrack(
      List<String> a, List<String> b, List<int[]> trace, int offset) {
    var result = new ArrayList<Row>();
    int x = a.size(), y = b.size();
    for (int depth = trace.size() - 1; depth >= 0; depth--) {
      int[] frontier = trace.get(depth);
      int k = x - y;
      int previousK =
          k == -depth || k != depth && frontier[offset + k - 1] < frontier[offset + k + 1]
              ? k + 1
              : k - 1;
      int previousX = frontier[offset + previousK], previousY = previousX - previousK;
      while (x > previousX && y > previousY) {
        result.add(new Row(' ', a.get(--x)));
        y--;
      }
      if (depth == 0) break;
      if (x == previousX) result.add(new Row('+', b.get(--y)));
      else result.add(new Row('-', a.get(--x)));
    }
    Collections.reverse(result);
    return result;
  }

  private static List<Row> replacement(List<String> a, List<String> b) {
    var result = new ArrayList<Row>();
    for (String line : a) result.add(new Row('-', line));
    for (String line : b) result.add(new Row('+', line));
    return result;
  }
}
