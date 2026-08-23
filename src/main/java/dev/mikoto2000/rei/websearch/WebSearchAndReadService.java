package dev.mikoto2000.rei.websearch;

import java.io.IOException;
import java.util.List;
import java.util.ArrayList;

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
    for (WebSearchResult result : searchResults) {
      if (results.size() >= validated.maxResults()) break;
      results.add(results.size() < validated.readTop() ? fetch(result) : notRequested(result));
    }
    return new WebSearchAndReadResponse(validated.query(), results);
  }

  private WebSearchAndReadItem fetch(WebSearchResult result) {
    UrlContentFetchResult fetched = urlContentFetchService.fetch(result.url());
    if (!fetched.success()) {
      return new WebSearchAndReadItem(result.title(), result.url(), result.snippet(), result.publishedAt(),
          null, null, "failed", fetched.errorType(), fetched.errorMessage(), false);
    }
    WebSearchPage page = webPageExtractor.extract(result, fetched.content());
    return new WebSearchAndReadItem(page.title(), page.url(), page.snippet(), page.publishedAt(),
        page.content(), null, "success", null, null, page.truncated());
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
