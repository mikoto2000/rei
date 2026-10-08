package dev.mikoto2000.rei.websearch;

import java.util.List;

/** Web 検索と本文取得の統合結果。 */
public record WebSearchAndReadResponse(String query, List<WebSearchAndReadItem> results,
    WebRetrievalAssessment assessment, List<String> omissions, int searchQueries, int pageAttempts) {

  public WebSearchAndReadResponse(String query, List<WebSearchAndReadItem> results) {
    this(query,results,WebRetrievalAssessment.unknown("assessment_not_performed"),List.of(),0,0);
  }

  public WebSearchAndReadResponse {
    results = results == null ? List.of() : List.copyOf(results);
    omissions = omissions == null ? List.of() : List.copyOf(omissions);
  }
}
