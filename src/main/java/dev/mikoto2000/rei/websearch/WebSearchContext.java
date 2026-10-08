package dev.mikoto2000.rei.websearch;

import java.util.List;

public record WebSearchContext(
    List<WebSearchPage> primaryResults,
    List<WebSearchPage> secondaryResults,
    @com.fasterxml.jackson.annotation.JsonIgnore List<WebSearchPage> orderedResults,
    WebRetrievalAssessment assessment, List<String> omissions, int searchQueries, int pageAttempts) {

  public WebSearchContext(List<WebSearchPage> primary, List<WebSearchPage> secondary, List<WebSearchPage> ordered) {
    this(primary,secondary,ordered,WebRetrievalAssessment.unknown("assessment_not_performed"),List.of(),0,0);
  }

  public WebSearchContext {
    primaryResults = List.copyOf(primaryResults); secondaryResults = List.copyOf(secondaryResults);
    orderedResults = List.copyOf(orderedResults);
    omissions = List.copyOf(omissions);
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
