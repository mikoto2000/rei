package dev.mikoto2000.rei.http.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyMap;
import static org.mockito.Mockito.calls;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.http.FetchOperation;
import dev.mikoto2000.rei.http.HttpFetchException;
import dev.mikoto2000.rei.http.HttpFetchObserver;
import dev.mikoto2000.rei.http.HttpFetchPolicy;
import dev.mikoto2000.rei.http.SafeHttpFetcher;
import dev.mikoto2000.rei.memory.util.SensitiveInfoDetector;
import dev.mikoto2000.rei.testsupport.AdjustableClock;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** HTTP通信応答キャッシュの結果と状態の契約を検証するテスト。 */
class HttpResponseCacheTest {
  /** 検証に使う参照先のURI。 */
  final URI uri = URI.create("https://example.com/page");

  /** 日時や期限の条件を再現するために使う検証時刻。 */
  final AdjustableClock clock = new AdjustableClock(Instant.parse("2026-01-01T00:00:00Z"));

  /** 検証に使うこの機能の動作設定。 */
  final HttpCacheProperties properties = new HttpCacheProperties();

  /** 検証に使う情報の取得元。 */
  final SafeHttpFetcher fetcher = mock(SafeHttpFetcher.class);

  /** 検証に使う呼び出し回数。 */
  final AtomicInteger calls = new AtomicInteger();

  /** {@code cache} の条件を再現するための補助情報を返す。 */
  HttpResponseCache cache() {
    return new HttpResponseCache(properties, clock, new SensitiveInfoDetector());
  }

  /** 検証で使用する要求を、指定された条件に合わせて用意する。 */
  HttpResponseCache.Request request(boolean force) {
    return request(
        uri,
        Map.of(),
        HttpFetchPolicy.html(Duration.ofSeconds(2)),
        HttpResponseCache.Namespace.PAGE,
        force);
  }

  /** 検証で使用する要求を、指定された条件に合わせて用意する。 */
  HttpResponseCache.Request request(
      URI url,
      Map<String, String> headers,
      HttpFetchPolicy policy,
      HttpResponseCache.Namespace kind,
      boolean force) {
    return new HttpResponseCache.Request(url, headers, policy, kind, force);
  }

  /** 検証用に、指定された対象の情報を、呼び出し元が参照できる形で返す。 */
  SafeHttpFetcher.Response get(HttpResponseCache cache, HttpResponseCache.Request request) {
    return cache.fetch(request, FetchOperation.active(), HttpFetchObserver.NONE, fetcher);
  }

  /** 検証で使用する応答を、指定された条件に合わせて用意する。 */
  SafeHttpFetcher.Response response(int status, Map<String, String> headers, String body, URI url) {
    return new SafeHttpFetcher.Response(
        status, headers, body.getBytes(StandardCharsets.UTF_8), url);
  }

  /** {@code fixed} の条件を再現するため、検証用の操作や状態を扱う。 */
  void fixed(int status, Map<String, String> headers, String body) {
    doAnswer(
            invocation -> {
              calls.incrementAndGet();
              ((FetchOperation) invocation.getArgument(3)).check();
              return response(status, headers, body, invocation.getArgument(0));
            })
        .when(fetcher)
        .fetch(any(), anyMap(), any(), any(), any());
  }

  /** 検索結果について、本文を再利用することを確認する。 */
  @Test
  void hitReusesBodyWithoutAnotherRawFetchAndDefensiveCopiesPreventPoisoning() {
    fixed(200, Map.of(), "evidence");
    try (var cache = cache()) {
      var first = get(cache, request(false));
      first.body()[0] = 'X';
      assertEquals(
          "evidence", new String(get(cache, request(false)).body(), StandardCharsets.UTF_8));
      assertEquals(1, calls.get());
      assertEquals(1, cache.size());
    }
  }

  /** 取得した情報の再利用で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void ttlExpirationActuallyReloadsTheSameEntry() {
    properties.setPageTtlSeconds(10);
    fixed(200, Map.of(), "evidence");
    try (var cache = cache()) {
      get(cache, request(false));
      clock.advance(Duration.ofSeconds(9));
      get(cache, request(false));
      assertEquals(1, calls.get());
      clock.advance(Duration.ofSeconds(1));
      get(cache, request(false));
      assertEquals(2, calls.get());
    }
  }

  /** 元の取得時刻を維持することを確認する。 */
  @Test
  void forceRefreshRevalidatesEtagAndKeepsOriginalFetchTime() {
    when(fetcher.fetch(any(), anyMap(), any(), any(), any()))
        .thenAnswer(
            invocation -> {
              if (calls.incrementAndGet() == 1)
                return response(200, Map.of("etag", "\"a\""), "evidence", uri);
              Map<String, String> headers = invocation.getArgument(1);
              assertEquals("\"a\"", headers.get("if-none-match"));
              assertTrue(headers.get("cache-control").contains("no-cache"));
              return response(304, Map.of("etag", "\"a\"", "cache-control", "max-age=30"), "", uri);
            });
    try (var cache = cache()) {
      var first = get(cache, request(false));
      clock.advance(Duration.ofSeconds(5));
      var updated = get(cache, request(true));
      assertEquals("evidence", new String(updated.body(), StandardCharsets.UTF_8));
      assertEquals(first.retrievedAt(), updated.retrievedAt());
      assertEquals(clock.instant(), updated.validatedAt());
      get(cache, request(false));
      assertEquals(2, calls.get());
    }
  }

  /** 取得した情報の再利用で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void lastModifiedIsUsedWhenEtagIsAbsent() {
    String modified = "Wed, 31 Dec 2025 00:00:00 GMT";
    when(fetcher.fetch(any(), anyMap(), any(), any(), any()))
        .thenAnswer(
            invocation -> {
              if (calls.incrementAndGet() == 1)
                return response(200, Map.of("last-modified", modified), "evidence", uri);
              assertEquals(
                  modified,
                  ((Map<String, String>) invocation.getArgument(1)).get("if-modified-since"));
              return response(304, Map.of(), "", uri);
            });
    try (var cache = cache()) {
      get(cache, request(false));
      get(cache, request(true));
      assertEquals(2, calls.get());
    }
  }

  /** 取得した情報の再利用で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void mismatched304CannotValidateAnUnrelatedBody() {
    when(fetcher.fetch(any(), anyMap(), any(), any(), any()))
        .thenAnswer(
            invocation ->
                calls.incrementAndGet() == 1
                    ? response(200, Map.of("etag", "\"a\""), "evidence", uri)
                    : response(304, Map.of("etag", "\"b\""), "", uri));
    try (var cache = cache()) {
      get(cache, request(false));
      assertThrows(HttpFetchException.class, () -> get(cache, request(true)));
      assertEquals(0, cache.size());
      assertEquals(0, cache.inFlight());
    }
  }

  /** 取得した情報の再利用で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void noCacheAndAgeRequireRevalidationBeforeReuse() {
    for (Map<String, String> headers :
        List.of(
            Map.of("cache-control", "no-cache", "etag", "\"a\""),
            Map.of("cache-control", "max-age=10", "age", "10"),
            Map.of("cache-control", "max-age=0"))) {
      calls.set(0);
      fixed(200, headers, "evidence");
      try (var cache = cache()) {
        get(cache, request(false));
        get(cache, request(false));
        assertEquals(2, calls.get());
      }
    }
  }

  /** 取得した情報の再利用で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void serverMaxAgeAndExpiresShortenConfiguredTtl() {
    for (Map<String, String> headers :
        List.of(
            Map.of("cache-control", "max-age=5"),
            Map.of(
                "date",
                "Thu, 01 Jan 2026 00:00:00 GMT",
                "expires",
                "Thu, 01 Jan 2026 00:00:05 GMT"))) {
      calls.set(0);
      fixed(200, headers, "evidence");
      try (var cache = cache()) {
        get(cache, request(false));
        clock.advance(Duration.ofSeconds(5));
        get(cache, request(false));
        assertEquals(2, calls.get());
      }
      clock.advance(Duration.ofSeconds(-5));
    }
  }

  /** 非公開なしについて、Cookieを保存することを確認する。 */
  @Test
  void privateNoStoreCookieAndVaryStarAreNeverRetained() {
    for (Map<String, String> headers :
        List.of(
            Map.of("cache-control", "private"),
            Map.of("cache-control", "no-store"),
            Map.of("set-cookie", "session=private"),
            Map.of("vary", "*"),
            Map.of("cache-control", "private=\"content-type\""))) {
      calls.set(0);
      fixed(200, headers, "evidence");
      try (var cache = cache()) {
        get(cache, request(false));
        get(cache, request(false));
        assertEquals(2, calls.get());
        assertEquals(0, cache.size());
      }
    }
  }

  /** 取得した情報の再利用で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void private304InvalidatesThePreviouslyPublicEntry() {
    when(fetcher.fetch(any(), anyMap(), any(), any(), any()))
        .thenAnswer(
            invocation ->
                calls.incrementAndGet() == 1
                    ? response(200, Map.of("etag", "\"a\""), "evidence", uri)
                    : response(304, Map.of("cache-control", "no-store"), "", uri));
    try (var cache = cache()) {
      get(cache, request(false));
      get(cache, request(true));
      assertEquals(0, cache.size());
    }
  }

  /** 取得した情報の再利用で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void errorResponsesAndExceptionsLeaveNoCachedOrInFlightState() {
    for (int status : List.of(404, 429, 500, 503)) {
      calls.set(0);
      fixed(status, Map.of(), "unavailable");
      try (var cache = cache()) {
        get(cache, request(false));
        get(cache, request(false));
        assertEquals(2, calls.get());
        assertEquals(0, cache.size());
        assertEquals(0, cache.inFlight());
      }
    }
    doThrow(new HttpFetchException(HttpFetchException.Code.NETWORK_ERROR))
        .when(fetcher)
        .fetch(any(), anyMap(), any(), any(), any());
    try (var cache = cache()) {
      assertThrows(HttpFetchException.class, () -> get(cache, request(false)));
      assertEquals(0, cache.inFlight());
    }
  }

  /** 取得した情報の再利用で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void queryProviderLimitHeadersAndPoliciesRemainSeparateConditions() {
    fixed(200, Map.of(), "evidence");
    try (var cache = cache()) {
      get(cache, request(false));
      get(
          cache,
          request(
              URI.create("https://example.com/page?q=other"),
              Map.of(),
              request(false).policy(),
              HttpResponseCache.Namespace.PAGE,
              false));
      get(
          cache,
          request(
              URI.create("https://provider.example/page?limit=2"),
              Map.of(),
              request(false).policy(),
              HttpResponseCache.Namespace.SEARCH,
              false));
      get(
          cache,
          request(
              uri,
              Map.of("accept", "text/html"),
              request(false).policy(),
              HttpResponseCache.Namespace.PAGE,
              false));
      var smaller =
          new HttpFetchPolicy(
              1024,
              1024,
              Duration.ofSeconds(1),
              Duration.ofSeconds(1),
              Duration.ofSeconds(2),
              1,
              null,
              null,
              false);
      get(cache, request(uri, Map.of(), smaller, HttpResponseCache.Namespace.PAGE, false));
      assertEquals(5, calls.get());
    }
  }

  /** 取得した情報の再利用で、秘密情報の保護と利用範囲が契約に従うことを確認する。 */
  @Test
  void authenticationAndSensitiveQueryOrBodyBypassStorage() {
    fixed(200, Map.of(), "evidence");
    try (var cache = cache()) {
      for (String credential : List.of("Bearer user-a", "Bearer user-b")) {
        var authenticated =
            request(
                uri,
                Map.of("authorization", credential),
                request(false).policy(),
                HttpResponseCache.Namespace.PAGE,
                false);
        get(cache, authenticated);
        get(cache, authenticated);
      }
      var secret =
          request(
              URI.create("https://example.com/page?token=private"),
              Map.of(),
              request(false).policy(),
              HttpResponseCache.Namespace.SEARCH,
              false);
      get(cache, secret);
      get(cache, secret);
      assertEquals(6, calls.get());
      assertEquals(0, cache.size());
    }
    for (String body :
        List.of(
            "token=private",
            "{\"access_token\":\"private\"}",
            "contact@example.com",
            "sk-abcdefghijklmnopq",
            "-----BEGIN PRIVATE KEY-----")) {
      fixed(200, Map.of(), body);
      try (var cache = cache()) {
        get(cache, request(false));
        assertEquals(0, cache.size(), body);
      }
    }
  }

  /** 取得した情報の再利用で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void privateOriginsAndRedirectedResponsesAreNotStored() {
    fixed(200, Map.of(), "evidence");
    URI local = URI.create("http://127.0.0.1:8080/page");
    var policy =
        new HttpFetchPolicy(
            1024,
            1024,
            Duration.ofSeconds(1),
            Duration.ofSeconds(1),
            Duration.ofSeconds(2),
            1,
            null,
            local,
            false);
    try (var cache = cache()) {
      var request = request(local, Map.of(), policy, HttpResponseCache.Namespace.SEARCH, false);
      get(cache, request);
      get(cache, request);
      assertEquals(2, calls.get());
      assertEquals(0, cache.size());
    }
    doReturn(response(200, Map.of(), "evidence", URI.create("https://example.com/final")))
        .when(fetcher)
        .fetch(any(), anyMap(), any(), any(), any());
    try (var cache = cache()) {
      get(cache, request(false));
      assertEquals(0, cache.size());
    }
  }

  /** 取得した情報の再利用で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void cacheCapacityBoundsResidentBodies() {
    properties.setMaxEntries(1);
    properties.setMaxBytes(2048);
    fixed(200, Map.of(), "evidence");
    try (var cache = cache()) {
      for (int i = 0; i < 5; i++)
        get(
            cache,
            request(
                URI.create("https://example.com/" + i),
                Map.of(),
                request(false).policy(),
                HttpResponseCache.Namespace.PAGE,
                false));
      assertEquals(1, cache.size());
      assertTrue(cache.bytes() <= 2048);
    }
  }
}
