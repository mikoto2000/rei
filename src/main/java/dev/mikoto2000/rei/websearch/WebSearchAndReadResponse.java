package dev.mikoto2000.rei.websearch;

import java.util.List;

/** Web 検索と本文取得の統合結果。 */
public record WebSearchAndReadResponse(String query, List<WebSearchAndReadItem> results) {

  public WebSearchAndReadResponse {
    results = results == null ? List.of() : List.copyOf(results);
  }
}
