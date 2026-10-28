package dev.mikoto2000.rei.core;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Pure exact editing against one immutable baseline, shared by all text editing tools. */
public final class TextEditEngine {
  private TextEditEngine() {}

  /** UTF-16 offsets and one-based inclusive line ranges in the old and new versions. */
  public record Range(int oldStart, int oldEnd, int newStart, int newEnd,
      int oldStartLine, int oldEndLine, int newStartLine, int newEndLine) {}

  /** Validated replacement and the exact edits that generated it. */
  public record Result(String text, List<Range> ranges) {}

  /** Bounded diagnostics contain locations, never source text or credentials. */
  public record Failure(boolean success, String code, String path, Integer editIndex,
      boolean retryable, boolean safeToReplay, List<Integer> candidateLines, String currentVersion,
      String guidance) {}

  static Failure detail(String code, String path, Integer index, List<Integer> candidates, String version) {
    boolean retryable = List.of("VERSION_MISMATCH", "AMBIGUOUS_MATCH", "TEXT_NOT_FOUND", "OVERLAPPING_EDITS").contains(code);
    return new Failure(false, code, path, index, retryable, false, candidates, version,
        "Inspect the saved proposal for UNKNOWN/TRANSACTION_FAILED. Otherwise use readMultiFile with path, candidate startLine/endLine and currentVersion as expectedVersion, then propose again. Never replay an old approval against a new baseline.");
  }

  static dev.mikoto2000.rei.core.diagnostic.FailureDiagnostics.ToolFailure failure(
      String code, String path, Integer index, List<Integer> candidates, String version) {
    try {
      return new dev.mikoto2000.rei.core.diagnostic.FailureDiagnostics.ToolFailure(code,
          new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(detail(code, path, index, candidates, version)));
    } catch (com.fasterxml.jackson.core.JsonProcessingException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  static void verifyVersion(String path, String baseline, String version) {
    String expected = version != null && version.startsWith("sha256:") ? version.substring(7) : version;
    String current = TextDocumentTransaction.hash(baseline);
    if (expected == null || !expected.equals(current))
      throw failure("VERSION_MISMATCH", path, null, List.of(), current);
  }

  static long inputBytes(String text) {
    return text == null ? 0 : text.getBytes(StandardCharsets.UTF_8).length;
  }

  static long inputBytes(List<TextChangeSetService.Edit> edits) {
    if (edits == null) return 0;
    long total = 0;
    for (var edit : edits) if (edit != null) total += inputBytes(edit.oldText()) + inputBytes(edit.newText());
    return total;
  }

  static void requestSize(long bytes) {
    if (bytes > TextWriteLimits.requestBytes()) throw failure("SIZE_LIMIT_EXCEEDED", null, null, List.of(), null);
  }

  /** Every hunk matches the original text; earlier edits never affect later matching. */
  public static Result edit(String baseline, List<TextChangeSetService.Edit> edits) {
    return edit(null, baseline, edits);
  }

  static Result edit(String path, String baseline, List<TextChangeSetService.Edit> edits) {
    if (edits == null || edits.isEmpty() || edits.size() > 64)
      throw new IllegalArgumentException("1..64 hunks required");
    record Hunk(int start, int end, String replacement) {}
    var hunks = new ArrayList<Hunk>();
    int inputBytes = 0;
    for (int index = 0; index < edits.size(); index++) {
      var edit = edits.get(index);
      if (edit == null || edit.oldText() == null || edit.newText() == null)
        throw new IllegalArgumentException("Hunk text required");
      TextDocumentTransaction.text(edit.oldText());
      TextDocumentTransaction.text(edit.newText());
      inputBytes += edit.oldText().getBytes(StandardCharsets.UTF_8).length
          + edit.newText().getBytes(StandardCharsets.UTF_8).length;
      if (inputBytes > TextWriteLimits.editBytes()) throw failure("SIZE_LIMIT_EXCEEDED", path, index, List.of(), null);
      if (edit.oldText().isEmpty()) throw new IllegalArgumentException("Nonempty oldText required");
      int start = baseline.indexOf(edit.oldText());
      if (start < 0) throw failure("TEXT_NOT_FOUND", path, index, List.of(), TextDocumentTransaction.hash(baseline));
      if (baseline.indexOf(edit.oldText(), start + 1) >= 0) {
        var candidates = new ArrayList<Integer>();
        for (int offset = start; offset >= 0 && candidates.size() < 8;
            offset = baseline.indexOf(edit.oldText(), offset + 1)) candidates.add(line(baseline, offset));
        throw failure("AMBIGUOUS_MATCH", path, index, List.copyOf(candidates), TextDocumentTransaction.hash(baseline));
      }
      hunks.add(new Hunk(start, start + edit.oldText().length(), edit.newText()));
    }
    hunks.sort(Comparator.comparingInt(Hunk::start));
    for (int i = 1; i < hunks.size(); i++)
      if (hunks.get(i).start() < hunks.get(i - 1).end())
        throw failure("OVERLAPPING_EDITS", path, null, List.of(), TextDocumentTransaction.hash(baseline));
    var result = new StringBuilder();
    var ranges = new ArrayList<Range>();
    int cursor = 0;
    for (var hunk : hunks) {
      result.append(baseline, cursor, hunk.start());
      int newStart = result.length();
      result.append(hunk.replacement());
      ranges.add(new Range(hunk.start(), hunk.end(), newStart, result.length(),
          line(baseline, hunk.start()), line(baseline, Math.max(hunk.start(), hunk.end() - 1)),
          line(result, newStart), line(result, Math.max(newStart, result.length() - 1))));
      cursor = hunk.end();
    }
    result.append(baseline, cursor, baseline.length());
    return new Result(result.toString(), List.copyOf(ranges));
  }

  private static int line(CharSequence text, int end) {
    int line = 1;
    for (int i = 0; i < end; i++) if (text.charAt(i) == '\n') line++;
    return line;
  }
}
