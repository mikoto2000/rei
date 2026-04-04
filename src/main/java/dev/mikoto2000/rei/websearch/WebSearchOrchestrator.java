package dev.mikoto2000.rei.websearch;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

@Service
public class WebSearchOrchestrator {

  private final WebSearchService webSearchService;
  private final WebPageFetcher webPageFetcher;

  public WebSearchOrchestrator(WebSearchService webSearchService, WebPageFetcher webPageFetcher) {
    this.webSearchService = webSearchService;
    this.webPageFetcher = webPageFetcher;
  }

  public WebSearchContext search(String query, Integer limit) throws IOException, InterruptedException {
    List<WebSearchResult> results = webSearchService.search(query, limit);
    List<WebSearchPage> pages = new ArrayList<>();
    for (WebSearchResult result : results) {
      pages.add(fetchPage(result));
    }
    return WebSearchContext.primaryOnly(pages);
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
