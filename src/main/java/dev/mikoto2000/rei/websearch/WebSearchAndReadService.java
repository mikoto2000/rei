package dev.mikoto2000.rei.websearch;

import java.io.IOException;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.mikoto2000.rei.urlfetch.UrlContentFetchService;
import dev.mikoto2000.rei.urlfetch.UrlContentFetchResult;

/** Web 検索と検索結果本文の取得を一つの操作として実行する。 */
@Service
public class WebSearchAndReadService {
  static final int DEFAULT_READ_TOP = 3;
  private static final Logger log = LoggerFactory.getLogger(WebSearchAndReadService.class);

  private final WebSearchService webSearchService;
  private final UrlContentFetchService urlContentFetchService;
  private final WebPageExtractor webPageExtractor;
  private final WebSearchProperties properties;
  private final WebSearchQueryPlanner planner;

  public WebSearchAndReadService(WebSearchService webSearchService, UrlContentFetchService urlContentFetchService,
      WebPageExtractor webPageExtractor, WebSearchProperties properties) {
    this(webSearchService, urlContentFetchService, webPageExtractor, properties, new WebSearchQueryPlanner());
  }
  @org.springframework.beans.factory.annotation.Autowired
  public WebSearchAndReadService(WebSearchService webSearchService, UrlContentFetchService urlContentFetchService,
      WebPageExtractor webPageExtractor, WebSearchProperties properties, WebSearchQueryPlanner planner) {
    this.webSearchService = webSearchService;
    this.urlContentFetchService = urlContentFetchService;
    this.webPageExtractor = webPageExtractor;
    this.properties = properties;
    this.planner = planner;
  }

  public WebSearchAndReadResponse searchAndRead(WebSearchAndReadRequest request)
      throws IOException, InterruptedException {
    long started = System.nanoTime();
    try { return searchAndReadObserved(request); }
    finally { WebSearchMetrics.OBSERVED.duration("total", System.nanoTime() - started); }
  }

  private WebSearchAndReadResponse searchAndReadObserved(WebSearchAndReadRequest request)
      throws IOException, InterruptedException {
    ValidatedRequest validated = validate(request);
    var metadataPlanner = validated.readTop() == 0 ? new WebSearchQueryPlanner() {
      public List<String> plan(String query) { return List.of(query); }
    } : planner;
    var candidates = WebSearchSelection.search(webSearchService, metadataPlanner,
        validated.query(), validated.maxResults(), properties);
    List<WebSearchResult> searchResults = candidates.stream().map(WebSearchSelection.Candidate::result).toList();
    List<WebSearchAndReadItem> results = new ArrayList<>();
    Map<String, UrlContentFetchResult> fetchCache = new LinkedHashMap<>();
    int readLimit = Math.min(validated.readTop(), properties.getMaxPageFetches());
    for (var candidate : candidates) {
      WebSearchResult result = candidate.result();
      dev.mikoto2000.rei.http.FetchScope.current().check();
      if (results.size() >= validated.maxResults()) break;
      results.add((results.size() < readLimit ? fetch(result, fetchCache) : notRequested(result)).withAliases(candidate.aliases()));
    }
    long successes = results.stream().filter(result -> "success".equals(result.fetchStatus())).count();
    long failures = results.stream().filter(result -> "failed".equals(result.fetchStatus())).count();
    WebSearchMetrics.OBSERVED.add("fetch_candidates", Math.min(searchResults.size(), readLimit));
    results = new ArrayList<>(WebContentDeduplication.items(results));
    results.forEach(item -> WebSearchMetrics.OBSERVED.text(item.content()));
    log.debug("webSearchAndRead completed: searchResults={}, fetchAttempts={}, fetchSuccesses={}, fetchFailures={}",
        results.size(), fetchCache.size(), successes, failures);
    return new WebSearchAndReadResponse(validated.query(), results);
  }

  private WebSearchAndReadItem fetch(WebSearchResult result, Map<String, UrlContentFetchResult> fetchCache) {
    UrlContentFetchResult fetched = fetchCache.computeIfAbsent(result.url(), this::safeFetch);
    if (!fetched.success()) {
      return new WebSearchAndReadItem(result.title(), result.url(), result.snippet(), result.publishedAt(),
          null, null, "failed", fetched.errorType(), fetched.errorMessage(), false);
    }
    try {
      WebSearchPage page = webPageExtractor.extract(result, fetched.content());
      var item = new WebSearchAndReadItem(page.title(), page.url(), page.snippet(), page.publishedAt(),
          page.content(), fetched.contentType(), "success", null, null, page.truncated(), page.fingerprint(), page.aliases());
      if (fetched.finalUrl() != null && !fetched.finalUrl().equals(result.url()))
        item = item.withAliases(List.of(new WebSourceAlias(fetched.finalUrl(), page.title(), page.publishedAt(), "http_redirect")));
      return item;
    } catch (RuntimeException exception) {
      dev.mikoto2000.rei.http.FetchOperation.propagateControls(exception);
      return new WebSearchAndReadItem(result.title(), result.url(), result.snippet(), result.publishedAt(),
          null, fetched.contentType(), "failed", "EXTRACTION_ERROR",
          exception.getMessage() == null ? "Failed to extract page content" : exception.getMessage(), false);
    }
  }

  private UrlContentFetchResult safeFetch(String url) {
    try {
      return urlContentFetchService.fetch(url, properties.fetchPolicy());
    } catch (RuntimeException exception) {
      dev.mikoto2000.rei.http.FetchOperation.propagateControls(exception);
      return UrlContentFetchResult.failure("FETCH_ERROR",
          exception.getMessage() == null ? "Failed to fetch URL" : exception.getMessage());
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
