package dev.mikoto2000.rei.websearch;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import dev.mikoto2000.rei.urlfetch.*;

class WebPageFetcherProvenanceTest {
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
