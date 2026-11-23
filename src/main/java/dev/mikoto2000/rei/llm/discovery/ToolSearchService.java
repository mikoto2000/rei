package dev.mikoto2000.rei.llm.discovery;

import java.text.Normalizer;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

/** Deterministic per-tool ranking followed by operation-set aggregation. */
public final class ToolSearchService {
  public List<String> search(ToolCatalog catalog, String query, int limit) {
    if (query == null || query.isBlank() || query.length() > 256 || limit < 1 || limit > 5)
      throw new IllegalArgumentException(
          "query must be 1..256 characters; maxResults must be 1..5");
    String normalized = normalize(query);
    String compact = normalized.replaceAll("\\s+", "");
    boolean exactName = catalog.isDeclaredName(query);
    var scores = new HashMap<String, Integer>();
    var representatives = new HashMap<String, String>();
    for (var entry : catalog.searchable()) {
      if (entry.basic()) continue;
      String name = normalize(entry.name());
      if (exactName && !name.equals(normalized)) continue;
      int score = name.equals(normalized) ? 10000 : name.contains(normalized) ? 1000 : 0;
      String aliases = normalize(entry.aliases());
      String description =
          normalize(catalog.callback(entry.name()).getToolDefinition().description());
      // Managed aliases work for Japanese phrases without adding a tokenizer or another model.
      for (String term : aliases.split("\\s+"))
        if (term.length() >= 2 && compact.contains(term))
          score = Math.max(score, 500 + term.length());
      if (aliases.contains(normalized)) score = Math.max(score, 500);
      if (description.contains(normalized)) score = Math.max(score, 100);
      if (score == 0) continue;
      scores.merge(entry.set(), score, Math::max);
      representatives.merge(entry.set(), entry.name(), (a, b) -> a.compareTo(b) < 0 ? a : b);
    }
    int best = scores.values().stream().mapToInt(Integer::intValue).max().orElse(0);
    return scores.keySet().stream()
        .filter(id -> best < 504 || scores.get(id) == best)
        .sorted(
            Comparator.<String>comparingInt(scores::get)
                .reversed()
                .thenComparing(representatives::get)
                .thenComparing(Comparator.naturalOrder()))
        .limit(limit)
        .toList();
  }

  private static String normalize(String s) {
    return Normalizer.normalize(s, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).strip();
  }
}
