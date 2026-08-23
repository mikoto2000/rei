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

  public WebSearchAndReadService(WebSearchService webSearchService, UrlContentFetchService urlContentFetchService,
      WebPageExtractor webPageExtractor, WebSearchProperties properties) {
    this.webSearchService = webSearchService;
    this.urlContentFetchService = urlContentFetchService;
    this.webPageExtractor = webPageExtractor;
    this.properties = properties;
  }

  public WebSearchAndReadResponse searchAndRead(WebSearchAndReadRequest request)
      throws IOException, InterruptedException {
    ValidatedRequest validated = validate(request);
    List<WebSearchResult> searchResults = webSearchService.search(validated.query(), validated.maxResults());
    List<WebSearchAndReadItem> results = new ArrayList<>();
    Map<String, UrlContentFetchResult> fetchCache = new LinkedHashMap<>();
    for (WebSearchResult result : searchResults) {
      if (results.size() >= validated.maxResults()) break;
      results.add(results.size() < validated.readTop() ? fetch(result, fetchCache) : notRequested(result));
    }
    long successes = results.stream().filter(result -> "success".equals(result.fetchStatus())).count();
    long failures = results.stream().filter(result -> "failed".equals(result.fetchStatus())).count();
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
    WebSearchPage page = webPageExtractor.extract(result, fetched.content());
    return new WebSearchAndReadItem(page.title(), page.url(), page.snippet(), page.publishedAt(),
        page.content(), fetched.contentType(), "success", null, null, page.truncated());
  }

  private UrlContentFetchResult safeFetch(String url) {
    try {
      return urlContentFetchService.fetch(url);
    } catch (RuntimeException exception) {
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
