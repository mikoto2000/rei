package dev.mikoto2000.rei.websearch;

import dev.mikoto2000.rei.core.contextbudget.LexicalTerms;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Web上の情報の調査に必要なWeb検索条件検索語句一覧を扱う。 */
final class WebQueryTerms {
  /** 停止。 */
  private static final Set<String> STOP =
      Set.of(
          "the",
          "and",
          "for",
          "how",
          "what",
          "is",
          "to",
          "of",
          "in",
          "official",
          "latest",
          "current",
          "recent",
          "compare",
          "sources",
          "documentation",
          "docs",
          "reference");

  /** Web上の情報の調査で受け渡す情報を、指定された内容から作成する。 */
  static List<String> of(String query) {
    if (query == null) return List.of();
    var terms = new LinkedHashSet<>(LexicalTerms.query(query));
    terms.removeAll(STOP);
    var literal =
        Pattern.compile("[a-zA-Z][a-zA-Z0-9_.$-]*|\\d+\\.\\d+(?:\\.\\d+){0,3}")
            .matcher(query.toLowerCase(Locale.ROOT));
    while (literal.find())
      if (literal.group().length() >= 2 && !STOP.contains(literal.group()))
        terms.add(literal.group());
    for (String term : List.copyOf(terms))
      // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
      if (term.codePoints().anyMatch(c -> c > 127) && term.length() > 4)
        for (int i = 0; i + 2 <= term.length() && terms.size() < 64; i++) {
          if (!Character.isSurrogate(term.charAt(i)) && !Character.isSurrogate(term.charAt(i + 1)))
            terms.add(term.substring(i, i + 2));
        }
    return terms.stream().limit(64).toList();
  }

  /** Web上の情報の調査で用いる実行頻度を返す。 */
  static int frequency(String text, String term) {
    String lower = Objects.toString(text, "").toLowerCase(Locale.ROOT);
    int count = 0, offset = 0;
    boolean ascii = term.codePoints().allMatch(c -> c < 128);
    while ((offset = lower.indexOf(term, offset)) >= 0) {
      int end = offset + term.length();
      if (!ascii
          || (offset == 0 || !Character.isLetterOrDigit(lower.charAt(offset - 1)))
              && (end == lower.length() || !Character.isLetterOrDigit(lower.charAt(end)))) count++;
      offset = end;
      if (count >= 64) break;
    }
    return count;
  }
}
