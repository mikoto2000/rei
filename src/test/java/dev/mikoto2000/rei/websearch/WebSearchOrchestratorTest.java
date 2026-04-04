package dev.mikoto2000.rei.websearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class WebSearchOrchestratorTest {

  @Test
  void searchFetchesPageContentForRawResults() throws Exception {
    WebSearchService webSearchService = Mockito.mock(WebSearchService.class);
    WebPageFetcher webPageFetcher = Mockito.mock(WebPageFetcher.class);
    WebSearchOrchestrator orchestrator = new WebSearchOrchestrator(webSearchService, webPageFetcher);

    WebSearchResult raw = new WebSearchResult("Title", "https://example.com", "Snippet", "2026-04-01");
    WebSearchPage page = new WebSearchPage("Title", "https://example.com", "Snippet", "2026-04-01", "Fetched content");
    when(webSearchService.search("spring ai", 3)).thenReturn(List.of(raw));
    when(webPageFetcher.fetch(raw)).thenReturn(page);

    WebSearchContext context = orchestrator.search("spring ai", 3);

    assertEquals(1, context.primaryResults().size());
    assertEquals("Fetched content", context.primaryResults().getFirst().content());
    assertTrue(context.secondaryResults().isEmpty());
  }
}
