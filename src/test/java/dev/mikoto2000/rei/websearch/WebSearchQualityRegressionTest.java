package dev.mikoto2000.rei.websearch;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

/** Deterministic evidence fixture: exact source recall and evidence coverage are separate assertions. */
class WebSearchQualityRegressionTest {
  @Test void finalImplementationReplaysTheUnchangedPhaseZeroUnverifiedFixture() throws Exception {
    var search=mock(WebSearchService.class);var fetch=mock(WebPageFetcher.class);
    for(String suffix:List.of(""," official"," latest")) {
      var source=new WebSearchResult("Java"+suffix,"https://example.com/"+suffix.trim(),"evidence",null);
      when(search.search("Java"+suffix,1)).thenReturn(List.of(source));
      when(fetch.fetch(source)).thenReturn(new WebSearchPage(source.title(),source.url(),"evidence",null,"evidence"));
    }
    var context=new WebSearchOrchestrator(search,fetch,new WebSearchQueryPlanner(),new WebSearchAggregator()).search("Java",1);
    verify(search,times(3)).search(anyString(),eq(1));verify(fetch,times(2)).fetch(any());
    assertEquals("https://example.com/",context.allResults().getFirst().url());assertEquals("evidence",context.allResults().getFirst().content());
    assertEquals("unknown",context.assessment().status());
    System.out.println("PHASE0_IDENTICAL_FIXTURE finalSearchServiceCalls=3 finalPageFetchCalls=2 exactUrlRecall=1 evidenceRetained=1 assessment=unknown");
  }
  @Test void improvesFrozenLegacyBaselineWithoutLosingEvidence() throws Exception {
    var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    io.micrometer.core.instrument.Metrics.addRegistry(registry);
    try {
      var search = mock(WebSearchService.class);
      var fetch = mock(WebPageFetcher.class);
      for (String suffix : List.of("", " official", " latest")) {
        var result = new WebSearchResult("Java" + suffix, "https://example.com/" + suffix.trim(), "evidence", null);
        when(search.search("Java" + suffix, 1)).thenReturn(List.of(result));
        when(fetch.fetch(result)).thenReturn(new WebSearchPage(result.title(), result.url(), "evidence", null, "Java evidence",false,
            WebContentDeduplication.fingerprint("Java evidence"),List.of(WebSourceAlias.from(result))));
      }
      var context = new WebSearchOrchestrator(search, fetch, new WebSearchQueryPlanner(), new WebSearchAggregator())
          .search("Java", 1);
      assertEquals(1, context.allResults().size());
      // Phase 0 measured 3 searches and 3 fetches for the same 1-result fixture.
      verify(search, times(1)).search(anyString(), eq(1));
      verify(fetch, times(1)).fetch(any());
      assertEquals("Java evidence", context.allResults().getFirst().content());
      assertEquals("https://example.com/", context.allResults().getFirst().url());
      assertEquals(1, registry.get("rei.web.search.events").tag("event", "fetch_candidates").counter().count());
      var additional = registry.find("rei.web.search.events").tag("event", "additional_searches").counter();
      assertEquals(0, additional == null ? 0 : additional.count());
    } finally { io.micrometer.core.instrument.Metrics.removeRegistry(registry); registry.close(); }
  }
  @ParameterizedTest
  @ValueSource(strings = {"Java streams", "Spring official docs", "API 2.0.1", "latest release",
      "複数サイトの確認", "日本語の技術情報", "English technical documentation", "rare result"})
  void retainsExpectedSourceAndEvidence(String query) throws Exception {
    var search = mock(WebSearchService.class);
    var fetch = mock(WebPageFetcher.class);
    var planner = mock(WebSearchQueryPlanner.class);
    var source = new WebSearchResult(query, "https://docs.example.com/reference", "required evidence", "2026-10-09");
    var page = new WebSearchPage(query, source.url(), source.snippet(), source.publishedAt(), "required evidence API 2.0.1");
    when(planner.plan(query)).thenReturn(List.of(query));
    when(search.search(query, 3)).thenReturn(List.of(source));
    when(fetch.fetch(source)).thenReturn(page);
    var context = new WebSearchOrchestrator(search, fetch, planner, new WebSearchAggregator()).search(query, 3);
    assertEquals(List.of(source.url()), context.allResults().stream().map(WebSearchPage::url).toList());
    assertTrue(context.allResults().getFirst().content().contains("required evidence"));
  }
  @Test void providerOutageRemainsExplicit() throws Exception {
    var search = mock(WebSearchService.class);
    var planner = mock(WebSearchQueryPlanner.class);
    when(planner.plan("outage")).thenReturn(List.of("outage"));
    when(search.search("outage", 3)).thenThrow(new java.io.IOException("provider unavailable"));
    assertThrows(java.io.IOException.class, () -> new WebSearchOrchestrator(search,
        mock(WebPageFetcher.class), planner, new WebSearchAggregator()).search("outage", 3));
  }
  @Test void unreadablePageRetainsSourceAndSnippet() throws Exception {
    var search = mock(WebSearchService.class);
    var fetch = mock(WebPageFetcher.class);
    var planner = mock(WebSearchQueryPlanner.class);
    var source = new WebSearchResult("unreadable", "https://example.com", "fallback evidence", null);
    when(planner.plan("unreadable")).thenReturn(List.of("unreadable"));
    when(search.search("unreadable", 3)).thenReturn(List.of(source));
    when(fetch.fetch(source)).thenThrow(new IllegalStateException("unavailable"));
    var page = new WebSearchOrchestrator(search, fetch, planner, new WebSearchAggregator())
        .search("unreadable", 3).allResults().getFirst();
    assertEquals(source.url(), page.url());
    assertEquals(source.snippet(), page.content());
  }
}
