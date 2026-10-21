package dev.mikoto2000.rei.core.contextbudget;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * モデルへ渡す文脈の管理に必要な語句による検索語句一覧を扱う。
 *
 * <p>Shared lexical normalization; preserves the indexed retrieval contract.
 */
public final class LexicalTerms {
  /** 外部から直接インスタンス化させないためのコンストラクター。 */
  private LexicalTerms() {}

  /** モデルへ渡す文脈の管理で用いる検索条件を返す。 */
  public static List<String> query(String query) {
    if (query == null || query.isBlank()) return List.of();
    return Arrays.stream(query.toLowerCase(Locale.ROOT).split("[^\\p{IsAlphabetic}\\p{IsDigit}]+"))
        .map(String::trim)
        .filter(term -> term.length() >= 2)
        .distinct()
        .toList();
  }
}
