package dev.mikoto2000.rei.core;

import dev.mikoto2000.rei.core.contextbudget.TokenEstimator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.function.UnaryOperator;

/**
 * ファイル結果の使用量と上限を管理する。
 *
 * <p>Tool output selection only; actual model usage remains owned by ModelCallBudget.
 */
final class FileResultBudget {
  /** 保存や受け渡しに用いるデータ形式の変換。 */
  private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
      new com.fasterxml.jackson.databind.ObjectMapper();

  /** トークン数。 */
  private static final TokenEstimator TOKENS = TokenEstimator.conservative();

  /** 共通の実行基盤において、fitsに該当するか判定する。 */
  static boolean fits(Object value, int bytes, int tokens) throws IOException {
    byte[] encoded = JSON.writeValueAsBytes(value);
    return encoded.length <= bytes
        && TOKENS.text(new String(encoded, StandardCharsets.UTF_8)) <= tokens;
  }

  /** 条件を満たさない操作を拒否するため、ファイル結果予算の前提条件を確認する。 */
  static void require(Object value, int bytes, int tokens) throws IOException {
    if (!fits(value, bytes, tokens))
      throw new IOException(
          "File result metadata exceeds byte/token budget; narrow the request or increase its budget");
  }

  /** 残っている予算に収めるため、ファイル結果予算を利用可能な量に整える。 */
  static <T> T fit(T value, int bytes, int tokens, UnaryOperator<T> smaller) throws IOException {
    for (int attempt = 0; attempt < 128; attempt++) {
      if (fits(value, bytes, tokens)) return value;
      T next = smaller.apply(value);
      if (next.equals(value)) break;
      value = next;
    }
    throw new IOException(
        "File result metadata exceeds byte/token budget; narrow the request or increase its budget");
  }

  /** 共通の実行基盤で用いるhalfを返す。 */
  static String half(String value) {
    int end = value.length() / 2;
    if (end > 0 && Character.isLowSurrogate(value.charAt(end))) end--;
    return value.substring(0, end);
  }
}
