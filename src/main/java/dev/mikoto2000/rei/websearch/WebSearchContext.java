package dev.mikoto2000.rei.websearch;

import java.util.List;

public record WebSearchContext(
    List<WebSearchPage> primaryResults,
    List<WebSearchPage> secondaryResults) {

  public static WebSearchContext primaryOnly(List<WebSearchPage> primaryResults) {
    return new WebSearchContext(primaryResults, List.of());
  }

  public List<WebSearchPage> allResults() {
    return java.util.stream.Stream.concat(primaryResults.stream(), secondaryResults.stream())
        .toList();
  }
}
