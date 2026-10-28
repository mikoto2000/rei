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

  /** Every hunk matches the original text; earlier edits never affect later matching. */
  public static Result edit(String baseline, List<TextChangeSetService.Edit> edits) {
    if (edits == null || edits.isEmpty() || edits.size() > 64)
      throw new IllegalArgumentException("1..64 hunks required");
    record Hunk(int start, int end, String replacement) {}
    var hunks = new ArrayList<Hunk>();
    int inputBytes = 0;
    for (var edit : edits) {
      if (edit == null || edit.oldText() == null || edit.newText() == null)
        throw new IllegalArgumentException("Hunk text required");
      TextDocumentTransaction.text(edit.oldText());
      TextDocumentTransaction.text(edit.newText());
      inputBytes += edit.oldText().getBytes(StandardCharsets.UTF_8).length
          + edit.newText().getBytes(StandardCharsets.UTF_8).length;
      if (inputBytes > 65536) throw new IllegalArgumentException("Hunk input exceeds 64KiB");
      if (edit.oldText().isEmpty()) throw new IllegalArgumentException("Nonempty oldText required");
      int start = baseline.indexOf(edit.oldText());
      if (start < 0) throw new IllegalArgumentException("Hunk oldText not found");
      if (baseline.indexOf(edit.oldText(), start + 1) >= 0)
        throw new IllegalArgumentException("Hunk oldText is ambiguous; include more context");
      hunks.add(new Hunk(start, start + edit.oldText().length(), edit.newText()));
    }
    hunks.sort(Comparator.comparingInt(Hunk::start));
    for (int i = 1; i < hunks.size(); i++)
      if (hunks.get(i).start() < hunks.get(i - 1).end())
        throw new IllegalArgumentException("Overlapping hunks");
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
