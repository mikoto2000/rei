package dev.mikoto2000.rei.core.contextbudget;

import dev.mikoto2000.rei.core.chat.AgentRunScope;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/** 元のツールの結果をモデルから呼び出せるツールとして提供する。 */
public class RawToolResultTools {
  public static dev.mikoto2000.rei.tooldelivery.ReadingRawAccess.Page readingPage(String response) {
    var page = RawResultPage.parse(response);
    return new dev.mikoto2000.rei.tooldelivery.ReadingRawAccess.Page(
        page.rawResultRef(), page.offset(), page.hasMore(), page.content());
  }

  /** 保存先。 */
  private final RawToolResultStore store;

  /** 概算。 */
  private final TokenEstimator estimator;

  /** ページトークン数。 */
  private final int pageTokens;

  /** モデルへ渡す文脈の管理を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public RawToolResultTools(RawToolResultStore store) {
    this(store, TokenEstimator.conservative());
  }

  /** モデルへ渡す文脈の管理を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public RawToolResultTools(RawToolResultStore store, TokenEstimator estimator) {
    this(store, estimator, 2048);
  }

  /** 指定された値や利用先を使って、RawToolResultToolsを初期化する。 */
  public RawToolResultTools(RawToolResultStore store, TokenEstimator estimator, int pageTokens) {
    this.store = store;
    this.estimator = estimator;
    this.pageTokens = pageTokens;
  }

  /** 必要な内容を参照できるよう、元のツールの結果を読み取る。 */
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

  /** モデルへ渡す文脈の管理で用いるページテキストを返す。 */
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

  /** モデルへ渡す文脈の管理で用いる安全性ヘッダーを返す。 */
  private static String safeHeader(String value) {
    return value
        .replace("\\", "\\\\")
        .replace("\r", "\\r")
        .replace("\n", "\\n")
        .replace("\t", "\\t");
  }

  /** 受け渡し先が扱える形式へ、指定された内容を変換する。 */
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
