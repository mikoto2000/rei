package dev.mikoto2000.rei.core.contextbudget;

import org.springframework.ai.tool.annotation.Tool;
import dev.mikoto2000.rei.core.chat.AgentRunScope;

public class RawToolResultTools {
  private final RawToolResultStore store;
  private final TokenEstimator estimator;
  private final int pageTokens;
  public RawToolResultTools(RawToolResultStore store) { this(store, TokenEstimator.conservative()); }
  public RawToolResultTools(RawToolResultStore store, TokenEstimator estimator) { this(store,estimator,2048); }
  public RawToolResultTools(RawToolResultStore store, TokenEstimator estimator, int pageTokens) { this.store = store; this.estimator = estimator; this.pageTokens=pageTokens; }
  @ResultDelivery(ResultDelivery.Kind.RAW_RESULT_PAGE)
  @Tool(description = "Read an exact contiguous page of a saved result. Offset is a UTF-16 character offset; limit 1..16000 is an upper bound. Follow nextOffset while hasMore. A page may shrink to its token budget.")
  public String readRawToolResult(String rawResultRef, int offset, int limit) {
    var owner = AgentRunScope.current();
    if (owner == null) throw new IllegalStateException("A run is required");
    if (offset < 0 || limit < 1 || limit > 16000) throw new IllegalArgumentException("INVALID_CURSOR");
    var result = store.read(owner.conversationId(), rawResultRef);
    return pageText(rawResultRef, result, offset, limit, pageTokens);
  }
  String pageText(String ref, RawToolResultStore.Result result, int offset, int limit, int budget) {
    String raw = result.rawResult();
    if (offset > raw.length() || offset > 0 && offset < raw.length()
        && Character.isHighSurrogate(raw.charAt(offset - 1)) && Character.isLowSurrogate(raw.charAt(offset)))
      throw new IllegalArgumentException("INVALID_CURSOR");
    int end = (int) Math.min(raw.length(), (long) offset + limit);
    if (end > offset && end < raw.length() && Character.isHighSurrogate(raw.charAt(end - 1)) && Character.isLowSurrogate(raw.charAt(end))) end--;
    int count = raw.codePointCount(offset, end), low = 0, high = count;
    while (low < high) {
      int mid = low + (high - low + 1) / 2;
      int candidate = raw.offsetByCodePoints(offset, mid);
      if (estimator.text(encode(ref, result, offset, candidate)) <= budget) low = mid;
      else high = mid - 1;
    }
    String encoded = encode(ref, result, offset, raw.offsetByCodePoints(offset, low));
    if (estimator.text(encoded) > budget || low == 0 && offset < raw.length())
      throw new IllegalStateException("CONTEXT_BUDGET_INSUFFICIENT");
    return encoded;
  }
  private static String safeHeader(String value) { return value.replace("\\","\\\\").replace("\r","\\r").replace("\n","\\n").replace("\t","\\t"); }
  private String encode(String ref, RawToolResultStore.Result result, int start, int end) {
    return "toolName: " + safeHeader(result.toolName()) + "\ntoolCallId: " + safeHeader(result.toolCallId())
        + "\nrawResultRef: " + ref + "\noffset: " + start + "\ntotalCharacters: " + result.rawResult().length()
        + "\nnextOffset: " + end + "\nhasMore: " + (end < result.rawResult().length()) + "\n"
        + result.rawResult().substring(start, end);
  }
}
