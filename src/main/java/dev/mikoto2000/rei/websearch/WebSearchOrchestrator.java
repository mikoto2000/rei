package dev.mikoto2000.rei.websearch;

import java.io.IOException;

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
    try (var refresh = dev.mikoto2000.rei.http.FetchScope.withForceRefresh(query != null && WebSearchSelection.needsFreshness(query))) { return searchObserved(query, limit); }
    finally { WebSearchMetrics.OBSERVED.duration("total", System.nanoTime() - started); }
  }

  private WebSearchContext searchObserved(String query, Integer limit) throws IOException, InterruptedException {
    int maximum = limit == null ? properties.getMaxResults() : Math.max(1, Math.min(limit, properties.getMaxResults()));
    var research = WebResearchPipeline.run(webSearchService,webSearchQueryPlanner,properties,batch,query,maximum,
        properties.getMaxPageFetches(),candidate -> webPageFetcher.fetch(candidate.result()),WebResearchPipeline.PAGES);
    WebSearchMetrics.OBSERVED.add("fetch_candidates",research.pageAttempts());
    var aggregated = webSearchAggregator.aggregate(research.results().stream()
        .filter(page -> !"not_requested".equals(page.fetchStatus())).toList(),maximum);
    var context = new WebSearchContext(aggregated.primaryResults(),aggregated.secondaryResults(),aggregated.allResults(),
        research.assessment(),research.omissions(),research.queries(),research.pageAttempts());
    context.allResults().forEach(page -> WebSearchMetrics.OBSERVED.text(page.content()));
    return context;
  }

}
