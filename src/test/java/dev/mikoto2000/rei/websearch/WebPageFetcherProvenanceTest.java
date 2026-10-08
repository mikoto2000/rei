package dev.mikoto2000.rei.websearch;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import dev.mikoto2000.rei.urlfetch.*;

class WebPageFetcherProvenanceTest {
  @Test void bothResearchPathsRetainHttpTimesAndExcerptPositions() throws Exception {
    var transport=mock(UrlContentFetchService.class);var search=mock(WebSearchService.class);var props=new WebSearchProperties();
    var source=new WebSearchResult("Java streams","https://example.com/page","Java streams",null);
    var retrieved=java.time.Instant.parse("2026-01-01T00:00:00Z");var validated=retrieved.plusSeconds(60);
    when(transport.fetch(eq(source.url()),any(dev.mikoto2000.rei.http.HttpFetchPolicy.class)))
        .thenReturn(UrlContentFetchResult.success("<h2 id='streams'>Java streams</h2><p>Java streams map</p>","text/html",source.url(),retrieved,validated));
    when(search.search("Java streams",1)).thenReturn(List.of(source));
    try(var batch=new WebFetchBatch(1,1,4)) {
      var response=new WebSearchAndReadService(search,transport,new WebPageExtractor(props),props,new WebSearchQueryPlanner(),batch)
          .searchAndRead(new WebSearchAndReadRequest("Java streams",1,1));
      var item=response.results().getFirst();assertEquals(retrieved,item.retrievedAt());assertEquals(validated,item.validatedAt());assertFalse(item.excerpts().isEmpty());
      try(var scope=WebExtractionScope.enter("Java streams")) {
        var page=new WebPageFetcher(props,new WebPageExtractor(props),transport).fetch(source);
        assertEquals(retrieved,page.retrievedAt());assertEquals(validated,page.validatedAt());assertFalse(page.excerpts().isEmpty());
      }
    }
  }
  @Test void redirectsRetainRequestedAndActualCitationUrls() throws Exception {
    var transport = mock(UrlContentFetchService.class);
    var result = new WebSearchResult("title", "https://example.com/old", "snippet", null);
    when(transport.fetch(eq(result.url()), any(dev.mikoto2000.rei.http.HttpFetchPolicy.class)))
        .thenReturn(UrlContentFetchResult.success("<p>evidence</p>", "text/html", "https://example.com/new"));
    var page = new WebPageFetcher(new WebSearchProperties(), new WebPageExtractor(), transport).fetch(result);
    assertEquals(List.of(result.url(), "https://example.com/new"), page.aliases().stream().map(WebSourceAlias::url).toList());
    assertEquals("http_redirect", page.aliases().get(1).evidenceType());
  }
}
