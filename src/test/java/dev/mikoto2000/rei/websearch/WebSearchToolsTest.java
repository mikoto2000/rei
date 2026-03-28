package dev.mikoto2000.rei.websearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class WebSearchToolsTest {

  @Test
  void webSearchDelegatesToService() throws Exception {
    WebSearchService service = Mockito.mock(WebSearchService.class);
    WebSearchTools tools = new WebSearchTools(service);
    List<WebSearchResult> expected = List.of(new WebSearchResult("Title", "https://example.com", "Snippet", null));
    when(service.search("spring ai", 3)).thenReturn(expected);

    List<WebSearchResult> actual = tools.webSearch("spring ai", 3);

    assertEquals(expected, actual);
    verify(service).search("spring ai", 3);
  }
}
