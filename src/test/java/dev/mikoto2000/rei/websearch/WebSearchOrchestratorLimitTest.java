package dev.mikoto2000.rei.websearch;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class WebSearchOrchestratorLimitTest {
  @Test void pageBudgetIsAppliedBeforeFetching() throws Exception {
    var search = mock(WebSearchService.class); var fetch = mock(WebPageFetcher.class);
    var properties = new WebSearchProperties(); properties.setMaxPageFetches(1);
    var sources = java.util.stream.IntStream.range(0, 3)
        .mapToObj(i -> new WebSearchResult("Java", "https://example.com/" + i, "Java", null)).toList();
    when(search.search("Java", 3)).thenReturn(sources);
    when(fetch.fetch(sources.getFirst())).thenReturn(new WebSearchPage("Java", sources.getFirst().url(), "Java", null, "evidence"));
    var context = new WebSearchOrchestrator(search, fetch, new WebSearchQueryPlanner(), new WebSearchAggregator(), properties).search("Java", 3);
    assertEquals(1, context.allResults().size()); verify(fetch, times(1)).fetch(any());
  }
  @Test void cancellationAfterMetadataStartsNoPageFetch() throws Exception {
    var search = mock(WebSearchService.class); var fetch = mock(WebPageFetcher.class);
    var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
    when(search.search(anyString(), anyInt())).thenAnswer(invocation -> {
      cancelled.set(true); return List.of(new WebSearchResult("Java", "https://example.com/", "Java", null));
    });
    try (var scope = dev.mikoto2000.rei.http.FetchScope.enter(new dev.mikoto2000.rei.http.FetchOperation(
        () -> { if (cancelled.get()) throw new java.util.concurrent.CancellationException(); }, Long.MAX_VALUE))) {
      assertThrows(java.util.concurrent.CancellationException.class, () -> new WebSearchOrchestrator(search, fetch,
          new WebSearchQueryPlanner(), new WebSearchAggregator()).search("Java", 1));
    }
    verifyNoInteractions(fetch);
  }
}
