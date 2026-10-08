package dev.mikoto2000.rei.websearch;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class WebResearchPipelineTest {
  @Test void supplementalProviderOutageRetainsAlreadyFetchedEvidence() throws Exception {
    var search=mock(WebSearchService.class);var properties=new WebSearchProperties();String query="Java streams";
    when(search.search(query,2)).thenReturn(List.of(result("one.example"),result("two.example")));
    when(search.search(query+" official",2)).thenThrow(new java.io.IOException("provider failed"));
    try(var batch=new WebFetchBatch(2,2,4)) {
      var research=WebResearchPipeline.run(search,new WebSearchQueryPlanner(),properties,batch,query,2,4,
          candidate -> page(candidate.result(),"Java partial guide "+candidate.result().url()),WebResearchPipeline.PAGES);
      assertEquals(2,research.results().size());assertEquals("unknown",research.assessment().status());
      assertTrue(research.omissions().contains("additional_search_failed"));assertEquals(2,research.queries());
    }
  }
  WebSearchResult result(String host) { return new WebSearchResult("Java streams", "https://" + host + "/page", "Java streams reference", null); }
  WebSearchPage page(WebSearchResult result, String body) {
    return new WebSearchPage(result.title(), result.url(), result.snippet(), null, body, false, WebContentDeduplication.fingerprint(body), List.of(WebSourceAlias.from(result)));
  }
  @Test void bodyDuplicatesTriggerOneBoundedSupplementAndRetainIndependentEvidence() throws Exception {
    var search = mock(WebSearchService.class); var planner = mock(WebSearchQueryPlanner.class); var properties = new WebSearchProperties();
    String query = "compare Java streams sources";
    when(planner.plan(query)).thenReturn(List.of(query, query + " official", query + " reference"));
    var initial = List.of(result("one.example"), result("two.example"), result("three.example"), result("four.example"), result("five.example"));
    var extra = List.of(result("six.example"), result("seven.example"));
    when(search.search(query, 5)).thenReturn(initial); when(search.search(query + " official", 5)).thenReturn(extra);
    var reads = new AtomicInteger();
    try (var batch = new WebFetchBatch(3, 2, 10)) {
      var research = WebResearchPipeline.run(search, planner, properties, batch, query, 5, 5, candidate -> {
        reads.incrementAndGet(); return page(candidate.result(), candidate.result().url().contains("six")
            ? "Java streams filtering independent guide." : "Java streams map copied explanation.");
      }, WebResearchPipeline.PAGES);
      assertEquals("sufficient", research.assessment().status()); assertTrue(reads.get() <= 5);
      assertTrue(research.results().stream().anyMatch(value -> value.url().contains("six")));
      verify(search).search(query + " official", 5); verify(search, atMost(3)).search(anyString(), eq(5));
    }
  }
  @Test void limitsStopRepeatedFailureWithoutRefetchingTheSameNormalizedUrl() throws Exception {
    var search = mock(WebSearchService.class); var planner = mock(WebSearchQueryPlanner.class); var properties = new WebSearchProperties();
    properties.setMaxSearchQueries(2); properties.setMaxPageFetches(2);
    when(planner.plan("topic")).thenReturn(List.of("topic", "topic official", "topic reference"));
    when(search.search(anyString(), eq(3))).thenReturn(List.of(new WebSearchResult("topic", "https://one.example/page?utm_source=x", "topic", null)));
    var reads = new AtomicInteger();
    try (var batch = new WebFetchBatch(1, 1, 4)) {
      var research = WebResearchPipeline.run(search, planner, properties, batch, "topic", 3, 2, candidate -> {
        reads.incrementAndGet(); throw new IllegalStateException("unavailable");
      }, WebResearchPipeline.PAGES);
      assertEquals("unknown", research.assessment().status()); assertEquals(1, reads.get());
      verify(search, times(2)).search(anyString(), eq(3));
    }
  }
}
