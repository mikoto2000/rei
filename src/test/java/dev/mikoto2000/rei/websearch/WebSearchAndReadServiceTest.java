package dev.mikoto2000.rei.websearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.io.IOException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import dev.mikoto2000.rei.urlfetch.UrlContentFetchService;
import dev.mikoto2000.rei.urlfetch.UrlContentFetchResult;

class WebSearchAndReadServiceTest {
  private WebSearchAndReadService service;
  private WebSearchService webSearchService;
  private UrlContentFetchService urlContentFetchService;

  @BeforeEach
  void setUp() {
    WebSearchProperties properties = new WebSearchProperties();
    properties.setMaxResults(5);
    webSearchService = Mockito.mock(WebSearchService.class);
    urlContentFetchService = Mockito.mock(UrlContentFetchService.class);
    service = new WebSearchAndReadService(webSearchService, urlContentFetchService,
        new WebPageExtractor(), properties);
  }

  @Test
  void rejectsBlankQuery() {
    IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
        () -> service.searchAndRead(new WebSearchAndReadRequest("  ", 5, 3)));
    assertEquals("query must not be blank", error.getMessage());
  }

  @Test
  void rejectsMaxResultsOutsideConfiguredRange() {
    assertThrows(IllegalArgumentException.class,
        () -> service.searchAndRead(new WebSearchAndReadRequest("query", 0, 0)));
    assertThrows(IllegalArgumentException.class,
        () -> service.searchAndRead(new WebSearchAndReadRequest("query", 6, 0)));
  }

  @Test
  void rejectsReadTopOutsideMaxResults() {
    assertThrows(IllegalArgumentException.class,
        () -> service.searchAndRead(new WebSearchAndReadRequest("query", 3, -1)));
    assertThrows(IllegalArgumentException.class,
        () -> service.searchAndRead(new WebSearchAndReadRequest("query", 3, 4)));
  }

  @Test
  void returnsRankedSearchMetadataWithoutFetchingWhenReadTopIsZero() throws Exception {
    org.mockito.Mockito.when(webSearchService.search("spring ai", 2)).thenReturn(List.of(
        new WebSearchResult("First", "https://example.com/1", "Snippet 1", "2026-01-01"),
        new WebSearchResult("Second", "https://example.com/2", "Snippet 2", null)));

    WebSearchAndReadResponse response = service.searchAndRead(new WebSearchAndReadRequest("spring ai", 2, 0));

    assertEquals("spring ai", response.query());
    assertEquals(List.of("First", "Second"), response.results().stream().map(WebSearchAndReadItem::title).toList());
    assertEquals(List.of("https://example.com/1", "https://example.com/2"),
        response.results().stream().map(WebSearchAndReadItem::url).toList());
    assertEquals(List.of("Snippet 1", "Snippet 2"),
        response.results().stream().map(WebSearchAndReadItem::snippet).toList());
    assertEquals(List.of("not_requested", "not_requested"),
        response.results().stream().map(WebSearchAndReadItem::fetchStatus).toList());
    Mockito.verifyNoInteractions(urlContentFetchService);
  }

  @Test
  void fetchesAndNormalizesOnlyRequestedTopResult() throws Exception {
    WebSearchResult first = new WebSearchResult("First", "https://example.com/1", "Snippet 1", null);
    WebSearchResult second = new WebSearchResult("Second", "https://example.com/2", "Snippet 2", null);
    org.mockito.Mockito.when(webSearchService.search("query", 2)).thenReturn(List.of(first, second));
    org.mockito.Mockito.when(urlContentFetchService.fetch(first.url())).thenReturn(UrlContentFetchResult.success("""
        <html><body><nav>menu</nav><article><p>Readable content.</p></article><script>ignore()</script></body></html>
        """, "text/html"));

    WebSearchAndReadResponse response = service.searchAndRead(new WebSearchAndReadRequest("query", 2, 1));

    assertEquals("success", response.results().get(0).fetchStatus());
    assertEquals("Readable content.", response.results().get(0).content());
    assertEquals("text/html", response.results().get(0).contentType());
    assertEquals("not_requested", response.results().get(1).fetchStatus());
    Mockito.verify(urlContentFetchService).fetch("https://example.com/1");
    Mockito.verify(urlContentFetchService, Mockito.never()).fetch("https://example.com/2");
  }

  @Test
  void marksContentTruncatedAtExtractorLimit() throws Exception {
    WebSearchResult result = new WebSearchResult("Title", "https://example.com/long", "Snippet", null);
    org.mockito.Mockito.when(webSearchService.search("query", 1)).thenReturn(List.of(result));
    org.mockito.Mockito.when(urlContentFetchService.fetch(result.url()))
        .thenReturn(UrlContentFetchResult.success("<html><body>" + "x".repeat(2_100) + "</body></html>"));

    WebSearchAndReadItem item = service.searchAndRead(new WebSearchAndReadRequest("query", 1, 1)).results().getFirst();

    assertEquals(2_000, item.content().length());
    org.junit.jupiter.api.Assertions.assertTrue(item.truncated());
  }

  @Test
  void keepsRankOrderAndReturnsPartialFetchFailures() throws Exception {
    WebSearchResult first = new WebSearchResult("First", "https://example.com/1", "S1", null);
    WebSearchResult second = new WebSearchResult("Second", "https://example.com/2", "S2", null);
    WebSearchResult third = new WebSearchResult("Third", "https://example.com/3", "S3", null);
    org.mockito.Mockito.when(webSearchService.search("query", 3)).thenReturn(List.of(first, second, third));
    org.mockito.Mockito.when(urlContentFetchService.fetch(first.url()))
        .thenReturn(UrlContentFetchResult.success("<p>one</p>"));
    org.mockito.Mockito.when(urlContentFetchService.fetch(second.url()))
        .thenReturn(UrlContentFetchResult.failure("NETWORK_ERROR", "timeout"));
    org.mockito.Mockito.when(urlContentFetchService.fetch(third.url()))
        .thenReturn(UrlContentFetchResult.success("<p>three</p>"));

    List<WebSearchAndReadItem> items = service.searchAndRead(
        new WebSearchAndReadRequest("query", 3, 3)).results();

    assertEquals(List.of("First", "Second", "Third"), items.stream().map(WebSearchAndReadItem::title).toList());
    assertEquals(List.of("success", "failed", "success"),
        items.stream().map(WebSearchAndReadItem::fetchStatus).toList());
    assertEquals("NETWORK_ERROR", items.get(1).errorType());
    assertEquals("timeout", items.get(1).errorMessage());
  }

  @Test
  void returnsSearchMetadataWhenEveryFetchFails() throws Exception {
    WebSearchResult first = new WebSearchResult("First", "https://example.com/1", "S1", null);
    WebSearchResult second = new WebSearchResult("Second", "https://example.com/2", "S2", null);
    org.mockito.Mockito.when(webSearchService.search("query", 2)).thenReturn(List.of(first, second));
    org.mockito.Mockito.when(urlContentFetchService.fetch(Mockito.anyString()))
        .thenReturn(UrlContentFetchResult.failure("NETWORK_ERROR", "offline"));

    List<WebSearchAndReadItem> items = service.searchAndRead(
        new WebSearchAndReadRequest("query", 2, 2)).results();

    assertEquals(2, items.size());
    assertEquals(List.of("failed", "failed"), items.stream().map(WebSearchAndReadItem::fetchStatus).toList());
    assertEquals(List.of("S1", "S2"), items.stream().map(WebSearchAndReadItem::snippet).toList());
  }

  @Test
  void fetchesDuplicateUrlOnlyOnceWhilePreservingResults() throws Exception {
    WebSearchResult first = new WebSearchResult("First", "https://example.com/same", "S1", null);
    WebSearchResult duplicate = new WebSearchResult("Duplicate", "https://example.com/same", "S2", null);
    org.mockito.Mockito.when(webSearchService.search("query", 2)).thenReturn(List.of(first, duplicate));
    org.mockito.Mockito.when(urlContentFetchService.fetch(first.url()))
        .thenReturn(UrlContentFetchResult.success("<p>shared</p>"));

    List<WebSearchAndReadItem> items = service.searchAndRead(
        new WebSearchAndReadRequest("query", 2, 2)).results();

    assertEquals(2, items.size());
    assertEquals(List.of("First", "Duplicate"), items.stream().map(WebSearchAndReadItem::title).toList());
    assertEquals(List.of("shared", "shared"), items.stream().map(WebSearchAndReadItem::content).toList());
    Mockito.verify(urlContentFetchService, Mockito.times(1)).fetch("https://example.com/same");
  }

  @Test
  void defaultsToConfiguredMaxResultsAndThreePageReads() throws Exception {
    List<WebSearchResult> results = java.util.stream.IntStream.rangeClosed(1, 5)
        .mapToObj(index -> new WebSearchResult("T" + index, "https://example.com/" + index, "S" + index, null))
        .toList();
    org.mockito.Mockito.when(webSearchService.search("query", 5)).thenReturn(results);
    org.mockito.Mockito.when(urlContentFetchService.fetch(Mockito.anyString()))
        .thenReturn(UrlContentFetchResult.success("<p>content</p>"));

    List<WebSearchAndReadItem> items = service.searchAndRead(
        new WebSearchAndReadRequest("query", null, null)).results();

    assertEquals(List.of("success", "success", "success", "not_requested", "not_requested"),
        items.stream().map(WebSearchAndReadItem::fetchStatus).toList());
  }

  @Test
  void propagatesSearchFailureAsWholeToolFailure() throws Exception {
    org.mockito.Mockito.when(webSearchService.search("query", 5)).thenThrow(new IOException("search offline"));

    IOException error = assertThrows(IOException.class,
        () -> service.searchAndRead(new WebSearchAndReadRequest("query", 5, 3)));

    assertEquals("search offline", error.getMessage());
    Mockito.verifyNoInteractions(urlContentFetchService);
  }
}
