package dev.mikoto2000.rei.memory.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * 長期記憶の保存と検索に必要な記憶検索検索語句一覧を扱う。
 *
 * <p>Bound plain-text terms; Japanese text also contributes trigrams without needing a
 * morphological engine.
 */
public final class MemorySearchTerms {
  /** 外部から直接インスタンス化させないためのコンストラクター。 */
  private MemorySearchTerms() {}

  /** 長期記憶の保存と検索で受け渡す情報を、指定された内容から作成する。 */
  public static List<String> of(String query) {
    if (query == null) return List.of();
    var terms = new LinkedHashSet<String>();
    for (String term : query.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}_]+")) {
      if (term.isBlank()) continue;
      terms.add(term);
      int[] points = term.codePoints().toArray();
      if (term.matches(".*[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}].*"))
        for (int i = 0; i + 3 <= points.length && terms.size() < 20; i++)
          terms.add(new String(points, i, 3));
      if (terms.size() >= 20) break;
    }
    return terms.stream().limit(20).toList();
  }
}
