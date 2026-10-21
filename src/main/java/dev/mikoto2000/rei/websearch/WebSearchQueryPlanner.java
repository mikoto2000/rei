package dev.mikoto2000.rei.websearch;

import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.stereotype.Component;

/** Web検索検索条件の計画を組み立てる。 */
@Component
public class WebSearchQueryPlanner {

  /** 依頼を進めるための候補や手順を組み立てる。 */
  public List<String> plan(String query) {
    LinkedHashSet<String> queries = new LinkedHashSet<>();
    String trimmed = query == null ? "" : query.trim();
    if (trimmed.isEmpty()) {
      return List.of();
    }
    queries.add(trimmed);
    queries.add(
        trimmed.toLowerCase(java.util.Locale.ROOT).matches("(?s).*(official|公式).*")
            ? trimmed + " reference"
            : trimmed + " official");
    if (WebSearchSelection.needsFreshness(trimmed)) queries.add(trimmed + " release date");
    else queries.add(trimmed + " reference");
    return List.copyOf(queries);
  }
}
