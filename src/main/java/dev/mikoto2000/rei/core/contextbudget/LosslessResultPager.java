package dev.mikoto2000.rei.core.contextbudget;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * モデルへ渡す文脈の管理に必要なLossless結果Pagerを扱う。
 *
 * <p>Exact UTF-16 cursors, with code point boundaries and a serialized-envelope token guard.
 */
public final class LosslessResultPager {
  /**
   * モデルへ渡す文脈の管理で受け渡すページを表す。
   *
   * @param rawResultRef 元の結果参照先
   * @param toolCallId ツール呼び出し識別子
   * @param toolName ツール名
   * @param offset 開始位置
   * @param nextOffset 次開始位置
   * @param totalCharacters 合計文字数
   * @param truncated 省略の有無
   * @param content 内容
   */
  public record Page(
      String rawResultRef,
      String toolCallId,
      String toolName,
      int offset,
      int nextOffset,
      int totalCharacters,
      boolean truncated,
      String content) {}

  /** 概算。 */
  private final TokenEstimator estimator;

  /** 保存や受け渡しに用いるデータ形式の変換。 */
  private final ObjectMapper mapper = new ObjectMapper();

  /** 指定された値や利用先を使って、LosslessResultPagerを初期化する。 */
  public LosslessResultPager(TokenEstimator estimator) {
    this.estimator = estimator;
  }

  /** モデルへ渡す文脈の管理で用いるページを返す。 */
  public String page(String ref, String call, String tool, String raw, int offset, int budget) {
    if (offset < 0
        || offset > raw.length()
        || offset > 0
            && offset < raw.length()
            && Character.isHighSurrogate(raw.charAt(offset - 1))
            && Character.isLowSurrogate(raw.charAt(offset)))
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

  /** 受け渡し先が扱える形式へ、指定された内容を変換する。 */
  private String encode(String ref, String call, String tool, String raw, int offset, int end) {
    try {
      return mapper.writeValueAsString(
          new Page(
              ref,
              call,
              tool,
              offset,
              end,
              raw.length(),
              end < raw.length(),
              raw.substring(offset, end)));
    } catch (java.io.IOException error) {
      throw new IllegalStateException("Cannot encode result page", error);
    }
  }
}
