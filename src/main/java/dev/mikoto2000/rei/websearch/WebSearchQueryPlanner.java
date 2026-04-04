package dev.mikoto2000.rei.websearch;

import java.util.LinkedHashSet;
import java.util.List;

import org.springframework.stereotype.Component;

@Component
public class WebSearchQueryPlanner {

  public List<String> plan(String query) {
    LinkedHashSet<String> queries = new LinkedHashSet<>();
    String trimmed = query == null ? "" : query.trim();
    if (trimmed.isEmpty()) {
      return List.of();
    }
    queries.add(trimmed);
    queries.add(trimmed + " official");
    queries.add(trimmed + " latest");
    return List.copyOf(queries);
  }
}
