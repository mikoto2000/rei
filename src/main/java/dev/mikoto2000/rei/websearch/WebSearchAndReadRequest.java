package dev.mikoto2000.rei.websearch;

/** Web 検索と上位ページ本文取得の入力。 */
public record WebSearchAndReadRequest(String query, Integer maxResults, Integer readTop,
    @org.springframework.ai.tool.annotation.ToolParam(required = false, description = "Revalidate cached web sources") Boolean forceRefresh) {
  public WebSearchAndReadRequest(String query, Integer maxResults, Integer readTop) { this(query, maxResults, readTop, null); }
}
