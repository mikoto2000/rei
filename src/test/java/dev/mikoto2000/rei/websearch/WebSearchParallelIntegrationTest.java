package dev.mikoto2000.rei.websearch;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import dev.mikoto2000.rei.urlfetch.*;

class WebSearchParallelIntegrationTest {
  List<WebSearchResult> sources() { return List.of(
      new WebSearchResult("topic first", "https://z.example/first", "fallback", null),
      new WebSearchResult("topic second", "https://a.example/second", "snippet", null),
      new WebSearchResult("topic third", "https://b.example/third", "snippet", null)); }
  @Test void orchestratorKeepsSelectedRankAndMarksFailedSnippetExplicitly() throws Exception {
    var properties = new WebSearchProperties(); var search = mock(WebSearchService.class);
    var fetch = mock(WebPageFetcher.class); var planner = new WebSearchQueryPlanner(); var input = sources();
    when(search.search("topic", 3)).thenReturn(input);
    when(fetch.fetch(input.getFirst())).thenThrow(new java.io.IOException("sensitive diagnostic"));
    when(fetch.fetch(input.get(1))).thenReturn(new WebSearchPage("second", input.get(1).url(), "snippet", null, "x".repeat(500)));
    when(fetch.fetch(input.get(2))).thenReturn(new WebSearchPage("third", input.get(2).url(), "snippet", null, "evidence"));
    try (var batch = new WebFetchBatch(3, 2, 32)) {
      var service = new WebSearchOrchestrator(search, fetch, planner, new WebSearchAggregator(), properties, batch);
      var result = service.search("topic", 3).allResults();
      assertEquals(input.stream().map(WebSearchResult::url).toList(), result.stream().map(WebSearchPage::url).toList());
      assertEquals("failed", result.getFirst().fetchStatus()); assertEquals("FETCH_ERROR", result.getFirst().errorType());
      assertEquals("fallback", result.getFirst().content()); assertEquals("success", result.get(1).fetchStatus());
    }
  }
  @Test void andReadUsesParallelPoolAndKeepsSearchRank() throws Exception {
    var properties = new WebSearchProperties(); var search = mock(WebSearchService.class);
    var fetch = mock(UrlContentFetchService.class); var input = sources();
    when(search.search("topic", 3)).thenReturn(input);
    var started = new CountDownLatch(3); var release = new CountDownLatch(1);
    when(fetch.fetch(anyString(), any())).thenAnswer(invocation -> {
      started.countDown(); assertTrue(release.await(2, TimeUnit.SECONDS));
      String url = invocation.getArgument(0); return UrlContentFetchResult.success("<body>topic " + url + "</body>", "text/html");
    });
    try (var batch = new WebFetchBatch(3, 2, 32); var caller = Executors.newSingleThreadExecutor()) {
      var service = new WebSearchAndReadService(search, fetch, new WebPageExtractor(), properties, new WebSearchQueryPlanner(), batch);
      var future = caller.submit(() -> service.searchAndRead(new WebSearchAndReadRequest("topic", 3, 3)));
      assertTrue(started.await(2, TimeUnit.SECONDS)); release.countDown();
      var result = future.get(2, TimeUnit.SECONDS).results();
      assertEquals(input.stream().map(WebSearchResult::url).toList(), result.stream().map(WebSearchAndReadItem::url).toList());
      assertTrue(result.stream().allMatch(item -> "success".equals(item.fetchStatus())));
    }
  }
}
