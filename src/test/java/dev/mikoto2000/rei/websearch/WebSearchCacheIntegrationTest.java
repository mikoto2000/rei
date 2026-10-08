package dev.mikoto2000.rei.websearch;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import dev.mikoto2000.rei.http.*;
import dev.mikoto2000.rei.http.cache.*;
import dev.mikoto2000.rei.memory.util.SensitiveInfoDetector;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class WebSearchCacheIntegrationTest {
  @Test void metadataReusesExactQueryAndLatestAlwaysRevalidates() throws Exception {
    var properties = new WebSearchProperties(); properties.setEnabled(true);
    var provider = new WebSearchProperties.ProviderProperties(); provider.setName("duckduckgo"); provider.setBaseUrl("https://provider.example/search");
    properties.setProviders(List.of(provider)); var raw = mock(SafeHttpFetcher.class); var calls = new AtomicInteger();
    doAnswer(invocation -> {
      calls.incrementAndGet(); var uri = (java.net.URI) invocation.getArgument(0);
      if (uri.getRawQuery().contains("latest")) {
        Map<String, String> headers = invocation.getArgument(1); assertTrue(headers.get("cache-control").contains("no-cache"));
      }
      ((HttpFetchObserver) invocation.getArgument(4)).request(false);
      return new SafeHttpFetcher.Response(200, Map.of("content-type", "text/html"),
          "<div class='result'><a class='result__a' href='https://example.com/topic'>topic</a><div class='result__snippet'>topic evidence</div></div>".getBytes(), uri);
    }).when(raw).fetch(any(), anyMap(), any(), any(), any());
    try (var cache = new HttpResponseCache(new HttpCacheProperties(), Clock.systemUTC(), new SensitiveInfoDetector())) {
      var service = new WebSearchService(properties, new JsonMapper(), raw, cache);
      assertEquals(service.search("topic", 1), service.search("topic", 1)); assertEquals(1, calls.get());
      service.search("other topic", 1); service.search("topic", 2); assertEquals(3, calls.get());
      service.search("latest topic", 1); service.search("latest topic", 1); assertEquals(5, calls.get());
    }
  }
  @Test void batchCapturesRefreshAndDoesNotLeakItIntoSubsequentWork() {
    try (var batch = new WebFetchBatch(1, 1, 2)) {
      var candidate = new WebSearchSelection.Candidate(new WebSearchResult("topic", "https://example.com", "", null), List.of(), 0);
      try (var refresh = FetchScope.withForceRefresh(true)) {
        assertTrue(batch.fetch(List.of(candidate), Duration.ofSeconds(2), ignored -> FetchScope.forceRefresh()).getFirst().value());
      }
      assertFalse(batch.fetch(List.of(candidate), Duration.ofSeconds(2), ignored -> FetchScope.forceRefresh()).getFirst().value());
    }
  }
}
