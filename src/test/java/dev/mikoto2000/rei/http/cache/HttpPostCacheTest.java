package dev.mikoto2000.rei.http.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.mikoto2000.rei.http.FetchOperation;
import dev.mikoto2000.rei.http.HttpFetchObserver;
import dev.mikoto2000.rei.http.HttpFetchPolicy;
import dev.mikoto2000.rei.http.SafeHttpFetcher;
import dev.mikoto2000.rei.memory.util.SensitiveInfoDetector;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class HttpPostCacheTest {
  private final AtomicInteger calls = new AtomicInteger();
  private final URI uri = URI.create("https://api.openai.com/v1/responses");
  private final HttpFetchPolicy policy = HttpFetchPolicy.html(Duration.ofSeconds(10));
  private final SafeHttpFetcher fetcher =
      new SafeHttpFetcher() {
        @Override
        public Response fetch(
            URI u,
            String method,
            byte[] body,
            Map<String, String> headers,
            HttpFetchPolicy p,
            FetchOperation op,
            HttpFetchObserver observer) {
          assertEquals("POST", method);
          assertFalse(headers.containsKey("if-none-match"));
          calls.incrementAndGet();
          return new Response(
              200,
              Map.of("content-type", "application/json", "etag", "\"v1\""),
              "{\"ok\":true}".getBytes(StandardCharsets.UTF_8),
              u);
        }
      };

  private HttpResponseCache.Request request(String body, String key) {
    return new HttpResponseCache.Request(
        uri,
        Map.of("Authorization", "Bearer " + key, "Content-Type", "application/json"),
        policy,
        HttpResponseCache.Namespace.SEARCH,
        false,
        "openai:5",
        "POST",
        body);
  }

  private void fetch(HttpResponseCache cache, HttpResponseCache.Request request) {
    cache.fetch(request, FetchOperation.active(), HttpFetchObserver.NONE, fetcher);
  }

  @Test
  void canonicalJsonCachesAndSeparatesQueryModelContextFiltersAndAuthentication() {
    var properties = new HttpCacheProperties();
    properties.setEnabled(true);
    try (var cache =
        new HttpResponseCache(properties, Clock.systemUTC(), new SensitiveInfoDetector())) {
      var first = request("{\"input\":\"one\",\"model\":\"gpt-4.1-mini\"}", "secret-one");
      fetch(cache, first);
      fetch(cache, request("{ \"model\": \"gpt-4.1-mini\", \"input\": \"one\" }", "secret-one"));
      assertEquals(1, calls.get());
      fetch(cache, request("{\"input\":\"two\",\"model\":\"gpt-4.1-mini\"}", "secret-one"));
      fetch(cache, request("{\"input\":\"one\",\"model\":\"gpt-4.1\"}", "secret-one"));
      fetch(cache, request("{\"input\":\"one\",\"search_context_size\":\"high\"}", "secret-one"));
      fetch(
          cache,
          request(
              "{\"input\":\"one\",\"filters\":{\"allowed_domains\":[\"example.com\"]}}",
              "secret-one"));
      fetch(cache, request(first.body(), "secret-two"));
      assertEquals(6, calls.get());
      assertFalse(first.toString().contains("secret-one"));
      assertFalse(first.toString().contains("one"));
    }
  }

  @Test
  void expiredPostIsRepeatedWithoutGetValidatorsAndForceRefreshBypasses() {
    var clock =
        new dev.mikoto2000.rei.testsupport.AdjustableClock(
            java.time.Instant.parse("2026-01-01T00:00:00Z"));
    var properties = new HttpCacheProperties();
    properties.setSearchTtlSeconds(1);
    try (var cache = new HttpResponseCache(properties, clock, new SensitiveInfoDetector())) {
      var request = request("{}", "key");
      fetch(cache, request);
      clock.advance(Duration.ofSeconds(2));
      fetch(cache, request);
      assertEquals(2, calls.get());
      fetch(
          cache,
          new HttpResponseCache.Request(
              uri,
              request.headers(),
              policy,
              HttpResponseCache.Namespace.SEARCH,
              true,
              request.variant(),
              "POST",
              "{}"));
      assertEquals(3, calls.get());
    }
  }

  @Test
  void endpointAndMethodHaveIndependentCacheEntries() {
    var gets = new AtomicInteger();
    var getFetcher =
        new SafeHttpFetcher() {
          @Override
          public Response fetch(
              URI u,
              Map<String, String> headers,
              HttpFetchPolicy p,
              FetchOperation op,
              HttpFetchObserver observer) {
            gets.incrementAndGet();
            return new Response(200, Map.of(), "ok".getBytes(StandardCharsets.UTF_8), u);
          }

          @Override
          public Response fetch(
              URI u,
              String method,
              byte[] body,
              Map<String, String> headers,
              HttpFetchPolicy p,
              FetchOperation op,
              HttpFetchObserver observer) {
            calls.incrementAndGet();
            return new Response(200, Map.of(), "ok".getBytes(StandardCharsets.UTF_8), u);
          }
        };
    try (var cache =
        new HttpResponseCache(
            new HttpCacheProperties(), Clock.systemUTC(), new SensitiveInfoDetector())) {
      for (int i = 0; i < 2; i++) {
        cache.fetch(
            new HttpResponseCache.Request(
                uri, Map.of(), policy, HttpResponseCache.Namespace.SEARCH, false, "same"),
            FetchOperation.active(),
            HttpFetchObserver.NONE,
            getFetcher);
        cache.fetch(
            new HttpResponseCache.Request(
                uri,
                Map.of("Content-Type", "application/json"),
                policy,
                HttpResponseCache.Namespace.SEARCH,
                false,
                "same",
                "POST",
                "{}"),
            FetchOperation.active(),
            HttpFetchObserver.NONE,
            getFetcher);
        cache.fetch(
            new HttpResponseCache.Request(
                URI.create("https://api.openai.com/v1/other"),
                Map.of("Content-Type", "application/json"),
                policy,
                HttpResponseCache.Namespace.SEARCH,
                false,
                "same",
                "POST",
                "{}"),
            FetchOperation.active(),
            HttpFetchObserver.NONE,
            getFetcher);
      }
      assertEquals(1, gets.get());
      assertEquals(2, calls.get());
    }
  }
}
