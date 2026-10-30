package dev.mikoto2000.rei.websearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyMap;
import static org.mockito.Mockito.calls;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import dev.mikoto2000.rei.http.FetchScope;
import dev.mikoto2000.rei.http.HttpFetchObserver;
import dev.mikoto2000.rei.http.SafeHttpFetcher;
import dev.mikoto2000.rei.http.cache.HttpCacheProperties;
import dev.mikoto2000.rei.http.cache.HttpResponseCache;
import dev.mikoto2000.rei.memory.util.SensitiveInfoDetector;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Web検索キャッシュ外部連携の結果と状態の契約を検証するテスト。 */
class WebSearchCacheIntegrationTest {
  /** HTTP 200 の認証ページを検索キャッシュから除き、次の検索で再取得する。 */
  @Test
  void challengeIsEvictedFromSearchCache() throws Exception {
    var properties = new WebSearchProperties();
    properties.setEnabled(true);
    var raw = mock(SafeHttpFetcher.class);
    var requests = new AtomicInteger();
    doAnswer(
            invocation -> {
              ((HttpFetchObserver) invocation.getArgument(4)).request(false);
              String html =
                  requests.incrementAndGet() == 1
                      ? "<form id='challenge-form'></form>"
                      : "<div class='result'><a class='result__a' href='https://example.com/'>Result</a></div>";
              return new SafeHttpFetcher.Response(
                  200,
                  Map.of("content-type", "text/html"),
                  html.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                  invocation.getArgument(0));
            })
        .when(raw)
        .fetch(any(), anyMap(), any(), any(), any());
    try (var cache =
        new HttpResponseCache(
            new HttpCacheProperties(), Clock.systemUTC(), new SensitiveInfoDetector())) {
      var service = new WebSearchService(properties, new JsonMapper(), raw, cache);
      assertThrows(WebSearchResponseException.class, () -> service.search("topic", 1));
      assertEquals(1, service.search("topic", 1).size());
      assertEquals(1, service.search("topic", 1).size());
      assertEquals(2, requests.get());
    }
  }

  /** 付随情報について、完全一致の検索条件を再利用することを確認する。 */
  @Test
  void metadataReusesExactQueryAndLatestAlwaysRevalidates() throws Exception {
    var properties = new WebSearchProperties();
    properties.setEnabled(true);
    var provider = new WebSearchProperties.ProviderProperties();
    provider.setName("duckduckgo");
    provider.setBaseUrl("https://provider.example/search");
    properties.setProviders(List.of(provider));
    var raw = mock(SafeHttpFetcher.class);
    var calls = new AtomicInteger();
    doAnswer(
            invocation -> {
              calls.incrementAndGet();
              var uri = (java.net.URI) invocation.getArgument(0);
              if (uri.getRawQuery().contains("latest")) {
                Map<String, String> headers = invocation.getArgument(1);
                assertTrue(headers.get("cache-control").contains("no-cache"));
              }
              ((HttpFetchObserver) invocation.getArgument(4)).request(false);
              return new SafeHttpFetcher.Response(
                  200,
                  Map.of("content-type", "text/html"),
                  "<div class='result'><a class='result__a' href='https://example.com/topic'>topic</a><div class='result__snippet'>topic evidence</div></div>"
                      .getBytes(),
                  uri);
            })
        .when(raw)
        .fetch(any(), anyMap(), any(), any(), any());
    try (var cache =
        new HttpResponseCache(
            new HttpCacheProperties(), Clock.systemUTC(), new SensitiveInfoDetector())) {
      var service = new WebSearchService(properties, new JsonMapper(), raw, cache);
      assertEquals(service.search("topic", 1), service.search("topic", 1));
      assertEquals(1, calls.get());
      service.search("other topic", 1);
      service.search("topic", 2);
      assertEquals(3, calls.get());
      service.search("latest topic", 1);
      service.search("latest topic", 1);
      assertEquals(5, calls.get());
    }
  }

  /** Web上の情報の調査で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void batchCapturesRefreshAndDoesNotLeakItIntoSubsequentWork() {
    try (var batch = new WebFetchBatch(1, 1, 2)) {
      var candidate =
          new WebSearchSelection.Candidate(
              new WebSearchResult("topic", "https://example.com", "", null), List.of(), 0);
      try (var refresh = FetchScope.withForceRefresh(true)) {
        assertTrue(
            batch
                .fetch(
                    List.of(candidate), Duration.ofSeconds(2), ignored -> FetchScope.forceRefresh())
                .getFirst()
                .value());
      }
      assertFalse(
          batch
              .fetch(
                  List.of(candidate), Duration.ofSeconds(2), ignored -> FetchScope.forceRefresh())
              .getFirst()
              .value());
    }
  }
}
