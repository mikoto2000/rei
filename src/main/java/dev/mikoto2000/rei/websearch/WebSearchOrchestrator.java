package dev.mikoto2000.rei.websearch;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

@Service
public class WebSearchOrchestrator {

  private final WebSearchService webSearchService;
  private final WebPageFetcher webPageFetcher;
  private final WebSearchQueryPlanner webSearchQueryPlanner;
  private final WebSearchAggregator webSearchAggregator;

  public WebSearchOrchestrator(
      WebSearchService webSearchService,
      WebPageFetcher webPageFetcher,
      WebSearchQueryPlanner webSearchQueryPlanner,
      WebSearchAggregator webSearchAggregator) {
    this.webSearchService = webSearchService;
    this.webPageFetcher = webPageFetcher;
    this.webSearchQueryPlanner = webSearchQueryPlanner;
    this.webSearchAggregator = webSearchAggregator;
  }

  public WebSearchContext search(String query, Integer limit) throws IOException, InterruptedException {
    Map<String, WebSearchResult> resultsByUrl = new LinkedHashMap<>();
    for (String plannedQuery : webSearchQueryPlanner.plan(query)) {
      for (WebSearchResult result : webSearchService.search(plannedQuery, limit)) {
        resultsByUrl.putIfAbsent(result.url(), result);
      }
    }
    List<WebSearchPage> pages = new ArrayList<>();
    for (WebSearchResult result : resultsByUrl.values()) {
      pages.add(fetchPage(result));
    }
    return webSearchAggregator.aggregate(pages, limit == null ? pages.size() : limit);
  }

  private WebSearchPage fetchPage(WebSearchResult result) throws IOException, InterruptedException {
    try {
      return webPageFetcher.fetch(result);
    } catch (RuntimeException e) {
      return fallbackPage(result);
    }
  }

  private WebSearchPage fallbackPage(WebSearchResult result) {
    return new WebSearchPage(
        result.title(),
        result.url(),
        result.snippet(),
        result.publishedAt(),
        result.snippet());
  }
}
