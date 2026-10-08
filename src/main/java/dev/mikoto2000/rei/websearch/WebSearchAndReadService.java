package dev.mikoto2000.rei.websearch;

import java.io.IOException;
import java.util.List;

import org.springframework.stereotype.Service;

import dev.mikoto2000.rei.urlfetch.UrlContentFetchService;
import dev.mikoto2000.rei.urlfetch.UrlContentFetchResult;

/** Web 検索と検索結果本文の取得を一つの操作として実行する。 */
@Service
public class WebSearchAndReadService {
  static final int DEFAULT_READ_TOP = 3;

  private final WebSearchService webSearchService;
  private final UrlContentFetchService urlContentFetchService;
  private final WebPageExtractor webPageExtractor;
  private final WebSearchProperties properties;
  private final WebSearchQueryPlanner planner;
  private final WebFetchBatch batch;

  public WebSearchAndReadService(WebSearchService webSearchService, UrlContentFetchService urlContentFetchService,
      WebPageExtractor webPageExtractor, WebSearchProperties properties) {
    this(webSearchService, urlContentFetchService, webPageExtractor, properties, new WebSearchQueryPlanner());
  }
  public WebSearchAndReadService(WebSearchService webSearchService, UrlContentFetchService urlContentFetchService,
      WebPageExtractor webPageExtractor, WebSearchProperties properties, WebSearchQueryPlanner planner) {
    this(webSearchService, urlContentFetchService, webPageExtractor, properties, planner, WebFetchBatch.sharedDefaults());
  }
  @org.springframework.beans.factory.annotation.Autowired
  public WebSearchAndReadService(WebSearchService webSearchService, UrlContentFetchService urlContentFetchService,
      WebPageExtractor webPageExtractor, WebSearchProperties properties, WebSearchQueryPlanner planner, WebFetchBatch batch) {
    this.webSearchService = webSearchService;
    this.urlContentFetchService = urlContentFetchService;
    this.webPageExtractor = webPageExtractor;
    this.properties = properties;
    this.planner = planner;
    this.batch = batch;
  }

  public WebSearchAndReadResponse searchAndRead(WebSearchAndReadRequest request)
      throws IOException, InterruptedException {
    long started = System.nanoTime();
    try (var refresh = dev.mikoto2000.rei.http.FetchScope.withForceRefresh(request != null &&
        (Boolean.TRUE.equals(request.forceRefresh()) || request.query() != null && WebSearchSelection.needsFreshness(request.query())))) {
      return searchAndReadObserved(request);
    }
    finally { WebSearchMetrics.OBSERVED.duration("total", System.nanoTime() - started); }
  }

  private WebSearchAndReadResponse searchAndReadObserved(WebSearchAndReadRequest request)
      throws IOException, InterruptedException {
    ValidatedRequest validated = validate(request);
    var metadataPlanner = validated.readTop() == 0 ? new WebSearchQueryPlanner() {
      public List<String> plan(String query) { return List.of(query); }
    } : planner;
    var research = WebResearchPipeline.run(webSearchService, metadataPlanner, properties, batch,
        validated.query(), validated.maxResults(), validated.readTop(), candidate -> fetch(candidate.result()),
        new WebResearchPipeline.Adapter<WebSearchAndReadItem>() {
          public WebSearchAndReadItem failed(WebSearchResult result, String error) {
            return WebSearchAndReadItem.fromPage(WebResearchPipeline.PAGES.failed(result,error),null,"Page acquisition did not complete");
          }
          public WebSearchAndReadItem notRequested(WebSearchResult result) { return WebSearchAndReadService.this.notRequested(result); }
          public WebSearchAndReadItem aliases(WebSearchAndReadItem value,List<WebSourceAlias> aliases) { return value.withAliases(aliases); }
          public WebSearchPage evidence(WebSearchAndReadItem value) { return value.asPage(); }
          public List<WebSearchAndReadItem> deduplicate(List<WebSearchAndReadItem> values) { return WebContentDeduplication.items(values); }
        });
    WebSearchMetrics.OBSERVED.add("fetch_candidates",research.pageAttempts());
    research.results().forEach(item -> WebSearchMetrics.OBSERVED.text(item.content()));
    return new WebSearchAndReadResponse(validated.query(),research.results(),research.assessment(),research.omissions(),
        research.queries(),research.pageAttempts());
  }
  private WebSearchAndReadItem fetch(WebSearchResult result) {
    UrlContentFetchResult fetched = safeFetch(result.url());
    if (!fetched.success()) {
      return new WebSearchAndReadItem(result.title(), result.url(), result.snippet(), result.publishedAt(),
          null, null, "failed", fetched.errorType(), fetched.errorMessage(), false);
    }
    try {
      WebSearchPage page = webPageExtractor.extract(result, fetched.content());
      var item = WebSearchAndReadItem.fromPage(page.withEvidence(fetched.retrievedAt(), fetched.validatedAt(),
          page.excerpts(), page.omissions()), fetched.contentType(), null);
      if (fetched.finalUrl() != null && !fetched.finalUrl().equals(result.url()))
        item = item.withAliases(List.of(new WebSourceAlias(fetched.finalUrl(), page.title(), page.publishedAt(), "http_redirect")));
      return item;
    } catch (RuntimeException exception) {
      dev.mikoto2000.rei.http.FetchOperation.propagateControls(exception);
      return new WebSearchAndReadItem(result.title(), result.url(), result.snippet(), result.publishedAt(),
          null, fetched.contentType(), "failed", "EXTRACTION_ERROR",
          "Failed to extract page content", false);
    }
  }

  private UrlContentFetchResult safeFetch(String url) {
    try {
      return urlContentFetchService.fetch(url, properties.fetchPolicy());
    } catch (RuntimeException exception) {
      dev.mikoto2000.rei.http.FetchOperation.propagateControls(exception);
      return UrlContentFetchResult.failure("FETCH_ERROR",
          "Failed to fetch URL");
    }
  }

  private WebSearchAndReadItem notRequested(WebSearchResult result) {
    return new WebSearchAndReadItem(result.title(), result.url(), result.snippet(), result.publishedAt(),
        null, null, "not_requested", null, null, false);
  }

  private ValidatedRequest validate(WebSearchAndReadRequest request) {
    if (request == null) throw new IllegalArgumentException("request must not be null");
    if (request.query() == null || request.query().isBlank()) {
      throw new IllegalArgumentException("query must not be blank");
    }
    int maxResults = request.maxResults() == null ? properties.getMaxResults() : request.maxResults();
    if (maxResults <= 0 || maxResults > properties.getMaxResults()) {
      throw new IllegalArgumentException("maxResults must be between 1 and " + properties.getMaxResults());
    }
    int readTop = request.readTop() == null ? Math.min(DEFAULT_READ_TOP, maxResults) : request.readTop();
    if (readTop < 0 || readTop > maxResults) {
      throw new IllegalArgumentException("readTop must be between 0 and maxResults");
    }
    return new ValidatedRequest(request.query().trim(), maxResults, readTop);
  }

  private record ValidatedRequest(String query, int maxResults, int readTop) {
  }
}
