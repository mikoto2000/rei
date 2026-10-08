package dev.mikoto2000.rei.http.cache;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import dev.mikoto2000.rei.http.*;
import dev.mikoto2000.rei.memory.util.SensitiveInfoDetector;
import dev.mikoto2000.rei.testsupport.AdjustableClock;

class HttpResponseCacheTest {
  final URI uri = URI.create("https://example.com/page");
  final AdjustableClock clock = new AdjustableClock(Instant.parse("2026-01-01T00:00:00Z"));
  final HttpCacheProperties properties = new HttpCacheProperties();
  final SafeHttpFetcher fetcher = mock(SafeHttpFetcher.class);
  final AtomicInteger calls = new AtomicInteger();
  HttpResponseCache cache() { return new HttpResponseCache(properties, clock, new SensitiveInfoDetector()); }
  HttpResponseCache.Request request(boolean force) { return request(uri, Map.of(), HttpFetchPolicy.html(Duration.ofSeconds(2)), HttpResponseCache.Namespace.PAGE, force); }
  HttpResponseCache.Request request(URI url, Map<String, String> headers, HttpFetchPolicy policy, HttpResponseCache.Namespace kind, boolean force) {
    return new HttpResponseCache.Request(url, headers, policy, kind, force);
  }
  SafeHttpFetcher.Response get(HttpResponseCache cache, HttpResponseCache.Request request) {
    return cache.fetch(request, FetchOperation.active(), HttpFetchObserver.NONE, fetcher);
  }
  SafeHttpFetcher.Response response(int status, Map<String, String> headers, String body, URI url) {
    return new SafeHttpFetcher.Response(status, headers, body.getBytes(StandardCharsets.UTF_8), url);
  }
  void fixed(int status, Map<String, String> headers, String body) {
    doAnswer(invocation -> {
      calls.incrementAndGet(); ((FetchOperation) invocation.getArgument(3)).check();
      return response(status, headers, body, invocation.getArgument(0));
    }).when(fetcher).fetch(any(), anyMap(), any(), any(), any());
  }
  @Test void hitReusesBodyWithoutAnotherRawFetchAndDefensiveCopiesPreventPoisoning() {
    fixed(200, Map.of(), "evidence");
    try (var cache = cache()) {
      var first = get(cache, request(false)); first.body()[0] = 'X';
      assertEquals("evidence", new String(get(cache, request(false)).body(), StandardCharsets.UTF_8));
      assertEquals(1, calls.get()); assertEquals(1, cache.size());
    }
  }
  @Test void ttlExpirationActuallyReloadsTheSameEntry() {
    properties.setPageTtlSeconds(10); fixed(200, Map.of(), "evidence");
    try (var cache = cache()) {
      get(cache, request(false)); clock.advance(Duration.ofSeconds(9)); get(cache, request(false)); assertEquals(1, calls.get());
      clock.advance(Duration.ofSeconds(1)); get(cache, request(false)); assertEquals(2, calls.get());
    }
  }
  @Test void forceRefreshRevalidatesEtagAndKeepsOriginalFetchTime() {
    when(fetcher.fetch(any(), anyMap(), any(), any(), any())).thenAnswer(invocation -> {
      if (calls.incrementAndGet() == 1) return response(200, Map.of("etag", "\"a\""), "evidence", uri);
      Map<String, String> headers = invocation.getArgument(1);
      assertEquals("\"a\"", headers.get("if-none-match")); assertTrue(headers.get("cache-control").contains("no-cache"));
      return response(304, Map.of("etag", "\"a\"", "cache-control", "max-age=30"), "", uri);
    });
    try (var cache = cache()) {
      var first = get(cache, request(false)); clock.advance(Duration.ofSeconds(5));
      var updated = get(cache, request(true)); assertEquals("evidence", new String(updated.body(), StandardCharsets.UTF_8));
      assertEquals(first.retrievedAt(), updated.retrievedAt()); assertEquals(clock.instant(), updated.validatedAt());
      get(cache, request(false)); assertEquals(2, calls.get());
    }
  }
  @Test void lastModifiedIsUsedWhenEtagIsAbsent() {
    String modified = "Wed, 31 Dec 2025 00:00:00 GMT";
    when(fetcher.fetch(any(), anyMap(), any(), any(), any())).thenAnswer(invocation -> {
      if (calls.incrementAndGet() == 1) return response(200, Map.of("last-modified", modified), "evidence", uri);
      assertEquals(modified, ((Map<String, String>) invocation.getArgument(1)).get("if-modified-since"));
      return response(304, Map.of(), "", uri);
    });
    try (var cache = cache()) { get(cache, request(false)); get(cache, request(true)); assertEquals(2, calls.get()); }
  }
  @Test void mismatched304CannotValidateAnUnrelatedBody() {
    when(fetcher.fetch(any(), anyMap(), any(), any(), any())).thenAnswer(invocation -> calls.incrementAndGet() == 1
        ? response(200, Map.of("etag", "\"a\""), "evidence", uri) : response(304, Map.of("etag", "\"b\""), "", uri));
    try (var cache = cache()) {
      get(cache, request(false)); assertThrows(HttpFetchException.class, () -> get(cache, request(true)));
      assertEquals(0, cache.size()); assertEquals(0, cache.inFlight());
    }
  }
  @Test void noCacheAndAgeRequireRevalidationBeforeReuse() {
    for (Map<String, String> headers : List.of(Map.of("cache-control", "no-cache", "etag", "\"a\""),
        Map.of("cache-control", "max-age=10", "age", "10"), Map.of("cache-control", "max-age=0"))) {
      calls.set(0); fixed(200, headers, "evidence");
      try (var cache = cache()) { get(cache, request(false)); get(cache, request(false)); assertEquals(2, calls.get()); }
    }
  }
  @Test void serverMaxAgeAndExpiresShortenConfiguredTtl() {
    for (Map<String, String> headers : List.of(Map.of("cache-control", "max-age=5"),
        Map.of("date", "Thu, 01 Jan 2026 00:00:00 GMT", "expires", "Thu, 01 Jan 2026 00:00:05 GMT"))) {
      calls.set(0); fixed(200, headers, "evidence");
      try (var cache = cache()) { get(cache, request(false)); clock.advance(Duration.ofSeconds(5)); get(cache, request(false)); assertEquals(2, calls.get()); }
      clock.advance(Duration.ofSeconds(-5));
    }
  }
  @Test void privateNoStoreCookieAndVaryStarAreNeverRetained() {
    for (Map<String, String> headers : List.of(Map.of("cache-control", "private"), Map.of("cache-control", "no-store"),
        Map.of("set-cookie", "session=private"), Map.of("vary", "*"), Map.of("cache-control", "private=\"content-type\""))) {
      calls.set(0); fixed(200, headers, "evidence");
      try (var cache = cache()) { get(cache, request(false)); get(cache, request(false)); assertEquals(2, calls.get()); assertEquals(0, cache.size()); }
    }
  }
  @Test void private304InvalidatesThePreviouslyPublicEntry() {
    when(fetcher.fetch(any(), anyMap(), any(), any(), any())).thenAnswer(invocation -> calls.incrementAndGet() == 1
        ? response(200, Map.of("etag", "\"a\""), "evidence", uri) : response(304, Map.of("cache-control", "no-store"), "", uri));
    try (var cache = cache()) { get(cache, request(false)); get(cache, request(true)); assertEquals(0, cache.size()); }
  }
  @Test void errorResponsesAndExceptionsLeaveNoCachedOrInFlightState() {
    for (int status : List.of(404, 429, 500, 503)) {
      calls.set(0); fixed(status, Map.of(), "unavailable");
      try (var cache = cache()) { get(cache, request(false)); get(cache, request(false)); assertEquals(2, calls.get()); assertEquals(0, cache.size()); assertEquals(0, cache.inFlight()); }
    }
    doThrow(new HttpFetchException(HttpFetchException.Code.NETWORK_ERROR)).when(fetcher).fetch(any(), anyMap(), any(), any(), any());
    try (var cache = cache()) { assertThrows(HttpFetchException.class, () -> get(cache, request(false))); assertEquals(0, cache.inFlight()); }
  }
  @Test void queryProviderLimitHeadersAndPoliciesRemainSeparateConditions() {
    fixed(200, Map.of(), "evidence");
    try (var cache = cache()) {
      get(cache, request(false));
      get(cache, request(URI.create("https://example.com/page?q=other"), Map.of(), request(false).policy(), HttpResponseCache.Namespace.PAGE, false));
      get(cache, request(URI.create("https://provider.example/page?limit=2"), Map.of(), request(false).policy(), HttpResponseCache.Namespace.SEARCH, false));
      get(cache, request(uri, Map.of("accept", "text/html"), request(false).policy(), HttpResponseCache.Namespace.PAGE, false));
      var smaller = new HttpFetchPolicy(1024, 1024, Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(2), 1, null, null, false);
      get(cache, request(uri, Map.of(), smaller, HttpResponseCache.Namespace.PAGE, false));
      assertEquals(5, calls.get());
    }
  }
  @Test void authenticationAndSensitiveQueryOrBodyBypassStorage() {
    fixed(200, Map.of(), "evidence");
    try (var cache = cache()) {
      for (String credential : List.of("Bearer user-a", "Bearer user-b")) {
        var authenticated = request(uri, Map.of("authorization", credential), request(false).policy(), HttpResponseCache.Namespace.PAGE, false);
        get(cache, authenticated); get(cache, authenticated);
      }
      var secret = request(URI.create("https://example.com/page?token=private"), Map.of(), request(false).policy(), HttpResponseCache.Namespace.SEARCH, false);
      get(cache, secret); get(cache, secret); assertEquals(6, calls.get()); assertEquals(0, cache.size());
    }
    for (String body : List.of("token=private", "{\"access_token\":\"private\"}", "contact@example.com", "sk-abcdefghijklmnopq", "-----BEGIN PRIVATE KEY-----")) {
      fixed(200, Map.of(), body);
      try (var cache = cache()) { get(cache, request(false)); assertEquals(0, cache.size(), body); }
    }
  }
  @Test void privateOriginsAndRedirectedResponsesAreNotStored() {
    fixed(200, Map.of(), "evidence");
    URI local = URI.create("http://127.0.0.1:8080/page");
    var policy = new HttpFetchPolicy(1024, 1024, Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(2), 1, null, local, false);
    try (var cache = cache()) {
      var request = request(local, Map.of(), policy, HttpResponseCache.Namespace.SEARCH, false);
      get(cache, request); get(cache, request); assertEquals(2, calls.get()); assertEquals(0, cache.size());
    }
    doReturn(response(200, Map.of(), "evidence", URI.create("https://example.com/final"))).when(fetcher).fetch(any(), anyMap(), any(), any(), any());
    try (var cache = cache()) { get(cache, request(false)); assertEquals(0, cache.size()); }
  }
  @Test void cacheCapacityBoundsResidentBodies() {
    properties.setMaxEntries(1); properties.setMaxBytes(2048); fixed(200, Map.of(), "evidence");
    try (var cache = cache()) {
      for (int i = 0; i < 5; i++) get(cache, request(URI.create("https://example.com/" + i), Map.of(), request(false).policy(), HttpResponseCache.Namespace.PAGE, false));
      assertEquals(1, cache.size()); assertTrue(cache.bytes() <= 2048);
    }
  }
}
