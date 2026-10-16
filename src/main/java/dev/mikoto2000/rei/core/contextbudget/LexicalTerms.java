package dev.mikoto2000.rei.core.contextbudget;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Shared lexical normalization; preserves the indexed retrieval contract. */
public final class LexicalTerms {
  private LexicalTerms() {}

  public static List<String> query(String query) {
    if (query == null || query.isBlank()) return List.of();
    return Arrays.stream(query.toLowerCase(Locale.ROOT).split("[^\\p{IsAlphabetic}\\p{IsDigit}]+"))
        .map(String::trim)
        .filter(term -> term.length() >= 2)
        .distinct()
        .toList();
  }
}
