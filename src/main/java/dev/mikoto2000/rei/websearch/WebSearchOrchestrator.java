package dev.mikoto2000.rei.websearch;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

@Service
public class WebSearchOrchestrator {

  private final WebSearchService webSearchService;
  private final WebPageFetcher webPageFetcher;
  private final WebSearchQueryPlanner webSearchQueryPlanner;
  private final WebSearchAggregator webSearchAggregator;
  private final WebSearchProperties properties;
  private final WebFetchBatch batch;

  public WebSearchOrchestrator(
      WebSearchService webSearchService,
      WebPageFetcher webPageFetcher,
      WebSearchQueryPlanner webSearchQueryPlanner,
      WebSearchAggregator webSearchAggregator) {
    this(webSearchService, webPageFetcher, webSearchQueryPlanner, webSearchAggregator, new WebSearchProperties());
  }
  public WebSearchOrchestrator(WebSearchService webSearchService, WebPageFetcher webPageFetcher,
      WebSearchQueryPlanner webSearchQueryPlanner, WebSearchAggregator webSearchAggregator, WebSearchProperties properties) {
    this(webSearchService, webPageFetcher, webSearchQueryPlanner, webSearchAggregator, properties, WebFetchBatch.sharedDefaults());
  }
  @org.springframework.beans.factory.annotation.Autowired
  public WebSearchOrchestrator(WebSearchService webSearchService, WebPageFetcher webPageFetcher,
      WebSearchQueryPlanner webSearchQueryPlanner, WebSearchAggregator webSearchAggregator, WebSearchProperties properties, WebFetchBatch batch) {
    this.webSearchService = webSearchService;
    this.webPageFetcher = webPageFetcher;
    this.webSearchQueryPlanner = webSearchQueryPlanner;
    this.webSearchAggregator = webSearchAggregator;
    this.properties = properties;
    this.batch = batch;
  }

  public WebSearchContext search(String query, Integer limit) throws IOException, InterruptedException {
    long started = System.nanoTime();
    try { return searchObserved(query, limit); }
    finally { WebSearchMetrics.OBSERVED.duration("total", System.nanoTime() - started); }
  }

  private WebSearchContext searchObserved(String query, Integer limit) throws IOException, InterruptedException {
    int maximum = limit == null ? properties.getMaxResults() : Math.max(1, Math.min(limit, properties.getMaxResults()));
    var selected = WebSearchSelection.search(webSearchService, webSearchQueryPlanner, query, maximum, properties);
    List<WebSearchPage> pages = new ArrayList<>();
    WebSearchMetrics.OBSERVED.add("fetch_candidates", Math.min(selected.size(), properties.getMaxPageFetches()));
    var toRead = selected.stream().limit(properties.getMaxPageFetches()).toList();
    var fetched = batch.fetch(toRead, java.time.Duration.ofSeconds(properties.getFetchBatchTimeoutSeconds()),
        candidate -> webPageFetcher.fetch(candidate.result()));
    for (int i = 0; i < fetched.size(); i++) {
      var candidate = toRead.get(i); var outcome = fetched.get(i);
      pages.add((outcome.success() ? outcome.value() : fallbackPage(candidate.result(), outcome.errorType())).withAliases(candidate.aliases()));
    }
    var context = webSearchAggregator.aggregate(WebContentDeduplication.pages(pages), maximum);
    context.allResults().forEach(page -> WebSearchMetrics.OBSERVED.text(page.content()));
    return context;
  }

  private WebSearchPage fallbackPage(WebSearchResult result, String errorType) {
    return new WebSearchPage(
        result.title(),
        result.url(),
        result.snippet(),
        result.publishedAt(),
        result.snippet(), false, null, List.of(WebSourceAlias.from(result)), "failed", errorType);
  }
}
