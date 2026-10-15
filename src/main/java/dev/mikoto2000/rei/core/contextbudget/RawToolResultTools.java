package dev.mikoto2000.rei.core.contextbudget;

import dev.mikoto2000.rei.core.chat.AgentRunScope;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

public class RawToolResultTools {
  private final RawToolResultStore store;
  private final TokenEstimator estimator;
  private final int pageTokens;

  public RawToolResultTools(RawToolResultStore store) {
    this(store, TokenEstimator.conservative());
  }

  public RawToolResultTools(RawToolResultStore store, TokenEstimator estimator) {
    this(store, estimator, 2048);
  }

  public RawToolResultTools(RawToolResultStore store, TokenEstimator estimator, int pageTokens) {
    this.store = store;
    this.estimator = estimator;
    this.pageTokens = pageTokens;
  }

  @ResultDelivery(ResultDelivery.Kind.RAW_RESULT_PAGE)
  @Tool(
      resultConverter = RawResultPageTextConverter.class,
      description =
          "Read an exact contiguous page of a saved result. All three arguments are required: rawResultRef, offset, limit. offset is a nonnegative UTF-16 character offset. Start with offset:0. To continue while hasMore=true, pass the previous response's numeric nextOffset as the input offset, for example {\"rawResultRef\":\"<same reference>\",\"offset\":3433,\"limit\":16000}. nextOffset is an output field, not an input argument. Do not quote numeric values. limit 1..16000 is an upper bound; a page may shrink to its token budget.")
  public String readRawToolResult(
      @ToolParam(
              description =
                  "Required saved result reference; keep the same reference across pages.")
          String rawResultRef,
      @ToolParam(
              description =
                  "Required integer UTF-16 character offset, at least 0. Initially 0; for continuation use the previous response's numeric nextOffset as offset. Do not send a nextOffset input field.")
          int offset,
      @ToolParam(description = "Required integer character limit from 1 to 16000 inclusive.")
          int limit) {
    var owner = AgentRunScope.current();
    if (owner == null) throw new IllegalStateException("A run is required");
    if (offset < 0 || limit < 1 || limit > 16000)
      throw new IllegalArgumentException("INVALID_CURSOR");
    var result = store.read(owner.conversationId(), rawResultRef);
    return pageText(rawResultRef, result, offset, limit, pageTokens);
  }

  String pageText(String ref, RawToolResultStore.Result result, int offset, int limit, int budget) {
    String raw = result.rawResult();
    if (offset > raw.length()
        || offset > 0
            && offset < raw.length()
            && Character.isHighSurrogate(raw.charAt(offset - 1))
            && Character.isLowSurrogate(raw.charAt(offset)))
      throw new IllegalArgumentException("INVALID_CURSOR");
    int end = (int) Math.min(raw.length(), (long) offset + limit);
    if (end > offset
        && end < raw.length()
        && Character.isHighSurrogate(raw.charAt(end - 1))
        && Character.isLowSurrogate(raw.charAt(end))) end--;
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

  private static String safeHeader(String value) {
    return value
        .replace("\\", "\\\\")
        .replace("\r", "\\r")
        .replace("\n", "\\n")
        .replace("\t", "\\t");
  }

  private String encode(String ref, RawToolResultStore.Result result, int start, int end) {
    return "toolName: "
        + safeHeader(result.toolName())
        + "\ntoolCallId: "
        + safeHeader(result.toolCallId())
        + "\nrawResultRef: "
        + ref
        + "\noffset: "
        + start
        + "\ntotalCharacters: "
        + result.rawResult().length()
        + "\nnextOffset: "
        + end
        + "\nhasMore: "
        + (end < result.rawResult().length())
        + "\n"
        + result.rawResult().substring(start, end);
  }
}
