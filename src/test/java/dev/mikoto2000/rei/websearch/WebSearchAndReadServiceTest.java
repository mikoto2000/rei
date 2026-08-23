package dev.mikoto2000.rei.websearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import dev.mikoto2000.rei.urlfetch.UrlContentFetchService;

class WebSearchAndReadServiceTest {
  private WebSearchAndReadService service;

  @BeforeEach
  void setUp() {
    WebSearchProperties properties = new WebSearchProperties();
    properties.setMaxResults(5);
    service = new WebSearchAndReadService(Mockito.mock(WebSearchService.class),
        Mockito.mock(UrlContentFetchService.class), new WebPageExtractor(), properties);
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
}
