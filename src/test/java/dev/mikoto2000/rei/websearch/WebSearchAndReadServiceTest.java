package dev.mikoto2000.rei.websearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import dev.mikoto2000.rei.urlfetch.UrlContentFetchService;

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
}
