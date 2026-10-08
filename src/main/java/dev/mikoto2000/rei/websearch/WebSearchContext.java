package dev.mikoto2000.rei.websearch;

import java.util.List;

public record WebSearchContext(
    List<WebSearchPage> primaryResults,
    List<WebSearchPage> secondaryResults,
    @com.fasterxml.jackson.annotation.JsonIgnore List<WebSearchPage> orderedResults) {

  public WebSearchContext {
    primaryResults = List.copyOf(primaryResults); secondaryResults = List.copyOf(secondaryResults);
    orderedResults = List.copyOf(orderedResults);
  }
  public WebSearchContext(List<WebSearchPage> primaryResults, List<WebSearchPage> secondaryResults) {
    this(primaryResults, secondaryResults, java.util.stream.Stream.concat(primaryResults.stream(), secondaryResults.stream()).toList());
  }

  public static WebSearchContext primaryOnly(List<WebSearchPage> primaryResults) {
    return new WebSearchContext(primaryResults, List.of());
  }

  public List<WebSearchPage> allResults() {
    return orderedResults;
  }
}
