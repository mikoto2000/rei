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
    long started = System.nanoTime();
    try { return searchObserved(query, limit); }
    finally { WebSearchMetrics.OBSERVED.duration("total", System.nanoTime() - started); }
  }

  private WebSearchContext searchObserved(String query, Integer limit) throws IOException, InterruptedException {
    Map<String, WebSearchResult> resultsByUrl = new LinkedHashMap<>();
    int searches = 0;
    for (String plannedQuery : webSearchQueryPlanner.plan(query)) {
      if (searches++ > 0) WebSearchMetrics.OBSERVED.add("additional_searches", 1);
      for (WebSearchResult result : webSearchService.search(plannedQuery, limit)) {
        if (resultsByUrl.putIfAbsent(result.url(), result) != null)
          WebSearchMetrics.OBSERVED.add("duplicate_urls", 1);
      }
    }
    List<WebSearchPage> pages = new ArrayList<>();
    WebSearchMetrics.OBSERVED.add("fetch_candidates", resultsByUrl.size());
    for (WebSearchResult result : resultsByUrl.values()) {
      pages.add(fetchPage(result));
    }
    var context = webSearchAggregator.aggregate(pages, limit == null ? pages.size() : limit);
    context.allResults().forEach(page -> WebSearchMetrics.OBSERVED.text(page.content()));
    return context;
  }

  private WebSearchPage fetchPage(WebSearchResult result) throws IOException, InterruptedException {
    try {
      return webPageFetcher.fetch(result);
    } catch (RuntimeException e) {
      dev.mikoto2000.rei.http.FetchOperation.propagateControls(e);
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
