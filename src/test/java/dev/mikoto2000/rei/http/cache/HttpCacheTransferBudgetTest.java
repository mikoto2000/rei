package dev.mikoto2000.rei.http.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyMap;
import static org.mockito.Mockito.calls;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import dev.mikoto2000.rei.http.FetchOperation;
import dev.mikoto2000.rei.http.HttpFetchException;
import dev.mikoto2000.rei.http.HttpFetchObserver;
import dev.mikoto2000.rei.http.HttpFetchPolicy;
import dev.mikoto2000.rei.http.SafeHttpFetcher;
import dev.mikoto2000.rei.http.TransferBudget;
import dev.mikoto2000.rei.http.TransferScope;
import dev.mikoto2000.rei.memory.util.SensitiveInfoDetector;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** HTTP通信キャッシュ引き継ぎ予算の結果と状態の契約を検証するテスト。 */
class HttpCacheTransferBudgetTest {
  /** 取得した情報の再利用で、入力や保存形式を契約に従って解釈し、情報を受け渡すことを確認する。 */
  @Test
  void revalidated304ChargesTheReusedDecodedBody() {
    var calls = new AtomicInteger();
    doAnswer(
            invocation ->
                calls.incrementAndGet() == 1
                    ? new SafeHttpFetcher.Response(
                        200, Map.of("etag", "\"a\""), "data".getBytes(), uri)
                    : new SafeHttpFetcher.Response(304, Map.of("etag", "\"a\""), new byte[0], uri))
        .when(raw)
        .fetch(any(), anyMap(), any(), any(), any());
    try (var cache = cache()) {
      get(cache);
      var budget = new TransferBudget(10, 3);
      try (var scope = TransferScope.enter(budget)) {
        var forced =
            new HttpResponseCache.Request(
                uri, Map.of(), request.policy(), HttpResponseCache.Namespace.PAGE, true);
        assertEquals(
            HttpFetchException.Code.TRANSFER_BUDGET,
            assertThrows(
                    HttpFetchException.class,
                    () -> cache.fetch(forced, FetchOperation.active(), HttpFetchObserver.NONE, raw))
                .code());
      }
      assertEquals(2, calls.get());
      assertEquals(0, budget.wireBytes());
      assertEquals(0, budget.decodedBytes());
    }
  }

  /** 検証に使う参照先のURI。 */
  final URI uri = URI.create("https://example.com/page");

  /** 検証に使う加工前の。 */
  final SafeHttpFetcher raw = mock(SafeHttpFetcher.class);

  /** 検証に使う要求。 */
  final HttpResponseCache.Request request =
      new HttpResponseCache.Request(
          uri,
          Map.of(),
          HttpFetchPolicy.html(Duration.ofSeconds(3)),
          HttpResponseCache.Namespace.PAGE,
          false);

  /** {@code cache} の条件を再現するための補助情報を返す。 */
  HttpResponseCache cache() {
    return new HttpResponseCache(
        new HttpCacheProperties(), Clock.systemUTC(), new SensitiveInfoDetector());
  }

  /** 検証用に、指定された対象の情報を、呼び出し元が参照できる形で返す。 */
  SafeHttpFetcher.Response get(HttpResponseCache cache) {
    return cache.fetch(request, FetchOperation.active(), HttpFetchObserver.NONE, raw);
  }

  /** キャッシュを使った場合も展開後の内容量を計上し、新たに通信していない転送量は加算しないことを確認する。 */
  @Test
  void cacheHitConsumesDecodedVolumeWithoutChargingNewWireTraffic() {
    var calls = new AtomicInteger();
    doAnswer(
            invocation -> {
              calls.incrementAndGet();
              TransferScope.wire(3);
              TransferScope.decoded(4);
              return new SafeHttpFetcher.Response(200, Map.of(), "data".getBytes(), uri);
            })
        .when(raw)
        .fetch(any(), anyMap(), any(), any(), any());
    var budget = new TransferBudget(10, 7);
    try (var cache = cache();
        var scope = TransferScope.enter(budget)) {
      get(cache);
      assertEquals(3, budget.wireBytes());
      assertEquals(4, budget.decodedBytes());
      assertEquals(
          HttpFetchException.Code.TRANSFER_BUDGET,
          assertThrows(HttpFetchException.class, () -> get(cache)).code());
      assertEquals(1, calls.get());
      assertEquals(3, budget.wireBytes());
    }
  }

  /** 取得した情報の再利用で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void differentAggregateBudgetsCannotStopEachOthersColdLoad() throws Exception {
    var started = new CountDownLatch(2);
    var release = new CountDownLatch(1);
    var calls = new AtomicInteger();
    doAnswer(
            invocation -> {
              int call = calls.incrementAndGet();
              started.countDown();
              if (call == 1) assertTrue(release.await(2, TimeUnit.SECONDS));
              TransferScope.wire(2);
              TransferScope.decoded(4);
              return new SafeHttpFetcher.Response(200, Map.of(), "data".getBytes(), uri);
            })
        .when(raw)
        .fetch(any(), anyMap(), any(), any(), any());
    try (var cache = cache();
        var pool = Executors.newFixedThreadPool(2)) {
      try {
        var first =
            pool.submit(
                () -> {
                  try (var scope = TransferScope.enter(new TransferBudget(1, 10))) {
                    return get(cache);
                  }
                });
        long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
        while (calls.get() == 0 && System.nanoTime() < deadline) Thread.sleep(5);
        assertEquals(1, calls.get());
        var second =
            pool.submit(
                () -> {
                  try (var scope = TransferScope.enter(new TransferBudget(10, 10))) {
                    return get(cache);
                  }
                });
        assertTrue(started.await(1, TimeUnit.SECONDS));
        release.countDown();
        assertEquals(
            HttpFetchException.Code.TRANSFER_BUDGET,
            ((HttpFetchException)
                    assertThrows(ExecutionException.class, () -> first.get(2, TimeUnit.SECONDS))
                        .getCause())
                .code());
        assertEquals("data", new String(second.get(2, TimeUnit.SECONDS).body()));
        assertEquals(2, calls.get());
      } finally {
        release.countDown();
      }
    }
  }
}
