package dev.mikoto2000.rei.websearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class WebSearchOrchestratorTest {

  @Test
  void searchFetchesPageContentForRawResults() throws Exception {
    WebSearchService webSearchService = Mockito.mock(WebSearchService.class);
    WebPageFetcher webPageFetcher = Mockito.mock(WebPageFetcher.class);
    WebSearchQueryPlanner queryPlanner = Mockito.mock(WebSearchQueryPlanner.class);
    WebSearchAggregator webSearchAggregator = Mockito.mock(WebSearchAggregator.class);
    WebSearchOrchestrator orchestrator = new WebSearchOrchestrator(webSearchService, webPageFetcher, queryPlanner, webSearchAggregator);

    WebSearchResult raw = new WebSearchResult("Title", "https://example.com", "Snippet", "2026-04-01");
    WebSearchPage page = new WebSearchPage("Title", "https://example.com", "Snippet", "2026-04-01", "Fetched content");
    when(queryPlanner.plan("spring ai")).thenReturn(List.of("spring ai"));
    when(webSearchService.search("spring ai", 3)).thenReturn(List.of(raw));
    when(webPageFetcher.fetch(raw)).thenReturn(page);
    when(webSearchAggregator.aggregate(List.of(page), 3)).thenReturn(WebSearchContext.primaryOnly(List.of(page)));

    WebSearchContext context = orchestrator.search("spring ai", 3);

    assertEquals(1, context.primaryResults().size());
    assertEquals("Fetched content", context.primaryResults().getFirst().content());
    assertTrue(context.secondaryResults().isEmpty());
  }

  @Test
  void searchExpandsQueriesAndRemovesDuplicateUrls() throws Exception {
    WebSearchService webSearchService = Mockito.mock(WebSearchService.class);
    WebPageFetcher webPageFetcher = Mockito.mock(WebPageFetcher.class);
    WebSearchQueryPlanner queryPlanner = Mockito.mock(WebSearchQueryPlanner.class);
    WebSearchAggregator webSearchAggregator = Mockito.mock(WebSearchAggregator.class);
    WebSearchOrchestrator orchestrator = new WebSearchOrchestrator(webSearchService, webPageFetcher, queryPlanner, webSearchAggregator);

    WebSearchResult duplicated = new WebSearchResult("Title", "https://example.com", "Snippet", "2026-04-01");
    WebSearchResult unique = new WebSearchResult("Another", "https://example.com/2", "Snippet 2", "2026-04-02");
    WebSearchPage duplicatedPage = new WebSearchPage("Title", "https://example.com", "Snippet", "2026-04-01", "Fetched content");
    WebSearchPage uniquePage = new WebSearchPage("Another", "https://example.com/2", "Snippet 2", "2026-04-02", "Fetched content 2");
    when(queryPlanner.plan("spring ai")).thenReturn(List.of("spring ai", "spring ai latest"));
    when(webSearchService.search("spring ai", 3)).thenReturn(List.of(duplicated));
    when(webSearchService.search("spring ai latest", 3)).thenReturn(List.of(duplicated, unique));
    when(webPageFetcher.fetch(duplicated)).thenReturn(duplicatedPage);
    when(webPageFetcher.fetch(unique)).thenReturn(uniquePage);
    when(webSearchAggregator.aggregate(List.of(duplicatedPage, uniquePage), 3))
        .thenReturn(WebSearchContext.primaryOnly(List.of(duplicatedPage, uniquePage)));

    WebSearchContext context = orchestrator.search("spring ai", 3);

    verify(webSearchService).search("spring ai", 3);
    verify(webSearchService).search("spring ai latest", 3);
    assertEquals(2, context.primaryResults().size());
    assertEquals(List.of("https://example.com", "https://example.com/2"),
        context.primaryResults().stream().map(WebSearchPage::url).toList());
  }
}
