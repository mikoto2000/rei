package dev.mikoto2000.rei.core.contextbudget;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Exact UTF-16 cursors, with code point boundaries and a serialized-envelope token guard. */
public final class LosslessResultPager {
  public record Page(String rawResultRef, String toolCallId, String toolName,
      int offset, int nextOffset, int totalCharacters, boolean truncated, String content) { }
  private final TokenEstimator estimator;
  private final ObjectMapper mapper = new ObjectMapper();
  public LosslessResultPager(TokenEstimator estimator) { this.estimator = estimator; }
  public String page(String ref, String call, String tool, String raw, int offset, int budget) {
    if (offset < 0 || offset > raw.length() || offset > 0 && offset < raw.length()
        && Character.isHighSurrogate(raw.charAt(offset - 1)) && Character.isLowSurrogate(raw.charAt(offset)))
      throw new IllegalArgumentException("INVALID_CURSOR");
    String empty = encode(ref, call, tool, raw, offset, offset);
    if (budget <= 0 || estimator.text(empty) > budget)
      throw new IllegalStateException("CONTEXT_BUDGET_INSUFFICIENT");
    // Search code points, not arbitrary UTF-16 boundaries. Encoding is measured after escaping.
    int available = raw.codePointCount(offset, raw.length());
    int low = 0, high = available;
    while (low < high) {
      int middle = low + (high - low + 1) / 2;
      int end = raw.offsetByCodePoints(offset, middle);
      if (estimator.text(encode(ref, call, tool, raw, offset, end)) <= budget) low = middle;
      else high = middle - 1;
    }
    if (low == 0 && offset < raw.length())
      throw new IllegalStateException("CONTEXT_BUDGET_INSUFFICIENT");
    return encode(ref, call, tool, raw, offset, raw.offsetByCodePoints(offset, low));
  }
  private String encode(String ref, String call, String tool, String raw, int offset, int end) {
    try { return mapper.writeValueAsString(new Page(ref, call, tool, offset, end,
        raw.length(), end < raw.length(), raw.substring(offset, end))); }
    catch (java.io.IOException error) { throw new IllegalStateException("Cannot encode result page", error); }
  }
}
