package dev.mikoto2000.rei.websearch;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class WebSearchSelectionTest {
  WebSearchProperties properties = new WebSearchProperties();
  WebSearchResult result(String title, String url, String date) { return new WebSearchResult(title, url, title, date); }
  @Test void enoughRelevantCandidatesStopAfterOriginalQuery() throws Exception {
    var search = mock(WebSearchService.class);
    when(search.search("Java streams", 1)).thenReturn(List.of(result("Java streams reference", "https://docs.oracle.com/en/java/streams", null)));
    var selected = WebSearchSelection.search(search, new WebSearchQueryPlanner(), "Java streams", 1, properties);
    assertEquals(1, selected.size()); verify(search, times(1)).search(anyString(), anyInt());
  }
  @Test void officialAndVersionRequirementsCauseAdditionalSearchDespiteEnoughResults() throws Exception {
    var search = mock(WebSearchService.class);
    when(search.search("Spring 2.0.1 official", 1)).thenReturn(List.of(result("Spring 2.0.1 tips", "https://docs.evil.example/a", null)));
    when(search.search("Spring 2.0.1 official reference", 1)).thenReturn(List.of(result("Spring 2.0.1 reference", "https://docs.spring.io/2.0.1/reference", null)));
    var selected = WebSearchSelection.search(search, new WebSearchQueryPlanner(), "Spring 2.0.1 official", 1, properties);
    assertEquals("docs.spring.io", java.net.URI.create(selected.getFirst().result().url()).getHost());
    verify(search).search("Spring 2.0.1 official reference", 1);
  }
  @Test void latestRequiresDatedEvidenceAndMultipleSourcesRequireDifferentDomains() {
    var old = result("Java latest release", "https://docs.oracle.com/old", "2020-01-01");
    var dated = result("Java latest release", "https://docs.oracle.com/new", LocalDate.now(ZoneOffset.UTC).toString());
    assertFalse(WebSearchSelection.sufficient("Java latest release", List.of(old), 1));
    assertTrue(WebSearchSelection.sufficient("Java latest release", List.of(dated), 1));
    assertFalse(WebSearchSelection.sufficient("Java compare sources", List.of(dated,
        result("Java compare sources", "https://docs.oracle.com/other", null)), 2));
  }
  @Test void normalizationRetainsMeaningfulQueryAndEncoding() {
    assertEquals("https://example.com/a?q=a%2Fb&version=2", WebSearchSelection.normalizeUrl("HTTPS://EXAMPLE.COM:443/x/../a?utm_source=x&q=a%2Fb&version=2#section"));
    assertNotEquals(WebSearchSelection.normalizeUrl("https://example.com/?q=a"), WebSearchSelection.normalizeUrl("https://example.com/?q=b"));
    assertNotEquals(WebSearchSelection.normalizeUrl("https://example.com/?q=a%2Fb"), WebSearchSelection.normalizeUrl("https://example.com/?q=a/b"));
    assertEquals("https://example.com/?ref=main&source=code", WebSearchSelection.normalizeUrl("https://example.com?ref=main&source=code"));
  }
  @Test void duplicatesKeepAliasesAndCandidatesAreCappedBeforeFetch() throws Exception {
    var search = mock(WebSearchService.class);
    when(search.search(anyString(), anyInt())).thenReturn(List.of(
        result("Java streams", "https://docs.oracle.com/a?utm_source=one", null),
        result("Java streams", "https://docs.oracle.com/a?utm_source=two", null),
        result("Java streams", "https://example.org/b", null),
        result("Java streams", "https://example.net/c", null)));
    var selected = WebSearchSelection.search(search, new WebSearchQueryPlanner(), "Java streams", 2, properties);
    assertEquals(2, selected.size()); assertEquals(2, selected.getFirst().aliases().size());
    assertEquals("https://example.org/b", selected.get(1).result().url());
  }
  @Test void unrelatedResultsAndWrongVersionsAreNotSufficient() {
    assertFalse(WebSearchSelection.sufficient("Java streams", List.of(result("weather forecast", "https://example.com", null)), 1));
    assertFalse(WebSearchSelection.sufficient("Spring 2.0.1 API", List.of(result("Spring 1.0 API", "https://docs.spring.io/1.0", null)), 1));
    assertTrue(WebSearchSelection.sufficient("Spring 2.0.1 API", List.of(result("Spring 2.0.1 API", "https://docs.spring.io/2.0.1", null)), 1));
    assertFalse(WebSearchSelection.official("JavaScript official", "https://docs.oracle.com/java"));
    assertFalse(WebSearchSelection.knownOfficial("https://docs.evil.example"));
    assertFalse(WebSearchSelection.knownOfficial("https://agency.gov.evil.example"));
  }
  @Test void expansionIsBoundedAndCancellationPropagates() throws Exception {
    var search = mock(WebSearchService.class);
    when(search.search(anyString(), anyInt())).thenReturn(List.of());
    assertTrue(WebSearchSelection.search(search, new WebSearchQueryPlanner(), "rare result", 2, properties).isEmpty());
    verify(search, atMost(3)).search(anyString(), anyInt());
    when(search.search(anyString(), anyInt())).thenThrow(new java.util.concurrent.CancellationException());
    assertThrows(java.util.concurrent.CancellationException.class, () -> WebSearchSelection.search(search, new WebSearchQueryPlanner(), "rare result", 2, properties));
  }
}
