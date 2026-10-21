package dev.mikoto2000.rei.http.cache;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
import dev.mikoto2000.rei.memory.util.SensitiveInfoDetector;
import dev.mikoto2000.rei.testsupport.AdjustableClock;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** HTTP通信キャッシュ単一Flightの結果と状態の契約を検証するテスト。 */
class HttpCacheSingleFlightTest {
  /** 検証に使う情報の取得元。 */
  final SafeHttpFetcher fetcher = mock(SafeHttpFetcher.class);

  /** 検証に使う参照先のURI。 */
  final URI uri = URI.create("https://example.com/page");

  /** 検証に使う呼び出し回数。 */
  final AtomicInteger calls = new AtomicInteger();

  /** 検証に使う開始、停止済み。 */
  final CountDownLatch started = new CountDownLatch(1), stopped = new CountDownLatch(1);

  /** 検証に使う解放。 */
  final AtomicBoolean release = new AtomicBoolean();

  /** {@code cache} の条件を再現するための補助情報を返す。 */
  HttpResponseCache cache(HttpCacheProperties properties) {
    return new HttpResponseCache(
        properties,
        new AdjustableClock(Instant.parse("2026-01-01T00:00:00Z")),
        new SensitiveInfoDetector());
  }

  /** 検証で使用する要求を、指定された条件に合わせて用意する。 */
  HttpResponseCache.Request request(URI url) {
    return new HttpResponseCache.Request(
        url,
        Map.of(),
        HttpFetchPolicy.html(Duration.ofSeconds(3)),
        HttpResponseCache.Namespace.PAGE,
        false);
  }

  /** 検証用に、指定された対象の情報を、呼び出し元が参照できる形で返す。 */
  SafeHttpFetcher.Response get(HttpResponseCache cache, URI url, AtomicBoolean cancelled) {
    return cache.fetch(
        request(url),
        new FetchOperation(
            () -> {
              if (cancelled.get()) throw new CancellationException();
            },
            Long.MAX_VALUE),
        HttpFetchObserver.NONE,
        fetcher);
  }

  /** {@code loader} の条件を再現するため、検証用の操作や状態を扱う。 */
  void loader(boolean privateResponse, boolean failure) {
    doAnswer(
            invocation -> {
              int call = calls.incrementAndGet();
              var operation = (FetchOperation) invocation.getArgument(3);
              started.countDown();
              try {
                if (call == 1)
                  while (!release.get()) {
                    operation.check();
                    Thread.sleep(10);
                  }
                if (failure) throw new HttpFetchException(HttpFetchException.Code.NETWORK_ERROR);
                return new SafeHttpFetcher.Response(
                    200,
                    privateResponse ? Map.of("cache-control", "no-store") : Map.of(),
                    ("evidence " + call).getBytes(StandardCharsets.UTF_8),
                    invocation.getArgument(0));
              } finally {
                stopped.countDown();
              }
            })
        .when(fetcher)
        .fetch(any(), anyMap(), any(), any(), any());
  }

  /** 検証用に、必要な状態が整ってから先へ進むため、HTTP通信キャッシュ単一Flight検証を待つ。 */
  void await(java.util.function.BooleanSupplier condition) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
    assertTrue(condition.getAsBoolean());
  }

  /** 取得した情報の再利用で、競合する要求でも、実行順序と作業の分離を守ることを確認する。 */
  @Test
  void identicalConcurrentRequestsPerformOneLoad() throws Exception {
    loader(false, false);
    try (var cache = cache(new HttpCacheProperties());
        var pool = Executors.newFixedThreadPool(2)) {
      var first = pool.submit(() -> get(cache, uri, new AtomicBoolean()));
      assertTrue(started.await(2, TimeUnit.SECONDS));
      var second = pool.submit(() -> get(cache, uri, new AtomicBoolean()));
      await(() -> cache.waiters() == 2);
      release.set(true);
      assertArrayEquals(first.get().body(), second.get().body());
      assertEquals(1, calls.get());
      await(() -> cache.activeLoads() == 0);
      assertEquals(0, cache.inFlight());
    }
  }

  /** 取得した情報の再利用の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void oneCancelledWaiterCannotCancelTheOtherWaitersLoad() throws Exception {
    loader(false, false);
    var cancel = new AtomicBoolean();
    try (var cache = cache(new HttpCacheProperties());
        var pool = Executors.newFixedThreadPool(2)) {
      var first = pool.submit(() -> get(cache, uri, cancel));
      assertTrue(started.await(2, TimeUnit.SECONDS));
      var second = pool.submit(() -> get(cache, uri, new AtomicBoolean()));
      await(() -> cache.waiters() == 2);
      cancel.set(true);
      assertInstanceOf(
          CancellationException.class,
          assertThrows(ExecutionException.class, () -> first.get(2, TimeUnit.SECONDS)).getCause());
      assertEquals(1, stopped.getCount());
      release.set(true);
      assertEquals(
          "evidence 1", new String(second.get(2, TimeUnit.SECONDS).body(), StandardCharsets.UTF_8));
      assertEquals(1, calls.get());
    }
  }

  /** 取得した情報の再利用の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void allCancelledWaitersStopTheLoaderAndRemoveTheFlight() throws Exception {
    loader(false, false);
    var cancel = new AtomicBoolean();
    try (var cache = cache(new HttpCacheProperties());
        var pool = Executors.newFixedThreadPool(2)) {
      var first = pool.submit(() -> get(cache, uri, cancel));
      assertTrue(started.await(2, TimeUnit.SECONDS));
      var second = pool.submit(() -> get(cache, uri, cancel));
      await(() -> cache.waiters() == 2);
      cancel.set(true);
      assertThrows(ExecutionException.class, () -> first.get(2, TimeUnit.SECONDS));
      assertThrows(ExecutionException.class, () -> second.get(2, TimeUnit.SECONDS));
      assertTrue(stopped.await(1, TimeUnit.SECONDS));
      await(() -> cache.activeLoads() == 0);
      assertEquals(0, cache.inFlight());
      assertEquals(0, cache.queuedLoads());
      assertEquals(0, cache.size());
    }
  }

  /** 取得した情報の再利用で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void unshareableResponseIsRetriedIndividuallyForJoinedWaiter() throws Exception {
    loader(true, false);
    try (var cache = cache(new HttpCacheProperties());
        var pool = Executors.newFixedThreadPool(2)) {
      var first = pool.submit(() -> get(cache, uri, new AtomicBoolean()));
      assertTrue(started.await(2, TimeUnit.SECONDS));
      var second = pool.submit(() -> get(cache, uri, new AtomicBoolean()));
      await(() -> cache.waiters() == 2);
      release.set(true);
      assertEquals("evidence 1", new String(first.get().body(), StandardCharsets.UTF_8));
      assertEquals("evidence 2", new String(second.get().body(), StandardCharsets.UTF_8));
      assertEquals(2, calls.get());
      assertEquals(0, cache.size());
    }
  }

  /** 取得した情報の再利用で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void failedSharedLoadIsRemovedAndNextRequestCanRetry() throws Exception {
    loader(false, true);
    try (var cache = cache(new HttpCacheProperties());
        var pool = Executors.newFixedThreadPool(2)) {
      var first = pool.submit(() -> get(cache, uri, new AtomicBoolean()));
      assertTrue(started.await(2, TimeUnit.SECONDS));
      var second = pool.submit(() -> get(cache, uri, new AtomicBoolean()));
      await(() -> cache.waiters() == 2);
      release.set(true);
      assertThrows(ExecutionException.class, first::get);
      assertThrows(ExecutionException.class, second::get);
      assertEquals(1, calls.get());
      await(() -> cache.inFlight() == 0);
      assertEquals(0, cache.size());
      loader(false, false);
      assertEquals(
          "evidence 2",
          new String(get(cache, uri, new AtomicBoolean()).body(), StandardCharsets.UTF_8));
    }
  }

  /** 上限内に収めた実行待ちおよび終了について、作業を受け付けないことを確認する。 */
  @Test
  void boundedQueueAndShutdownRejectWorkWithoutLeavingEntries() throws Exception {
    loader(false, false);
    var properties = new HttpCacheProperties();
    properties.setLoadParallelism(1);
    properties.setLoadQueueCapacity(1);
    properties.setMaxInFlight(2);
    var cache = cache(properties);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var first = pool.submit(() -> get(cache, uri, new AtomicBoolean()));
      assertTrue(started.await(2, TimeUnit.SECONDS));
      var second =
          pool.submit(
              () -> get(cache, URI.create("https://second.example/page"), new AtomicBoolean()));
      await(() -> cache.inFlight() == 2);
      assertEquals(
          HttpFetchException.Code.FETCH_REJECTED,
          assertThrows(
                  HttpFetchException.class,
                  () -> get(cache, URI.create("https://third.example/page"), new AtomicBoolean()))
              .code());
      cache.close();
      assertThrows(ExecutionException.class, first::get);
      assertThrows(ExecutionException.class, second::get);
      await(() -> cache.activeLoads() == 0);
      assertEquals(0, cache.inFlight());
      assertEquals(0, cache.queuedLoads());
      assertThrows(HttpFetchException.class, () -> get(cache, uri, new AtomicBoolean()));
    } finally {
      cache.close();
    }
  }

  /** 取得した情報の再利用で、時刻や期限に応じて、待機・実行・保存の扱いを切り替えることを確認する。 */
  @Test
  void firstWaitersShortDeadlineDoesNotBecomeTheSharedLoadDeadline() throws Exception {
    loader(false, false);
    try (var cache = cache(new HttpCacheProperties());
        var pool = Executors.newFixedThreadPool(2)) {
      var first =
          pool.submit(
              () ->
                  cache.fetch(
                      request(uri),
                      FetchOperation.active().withTimeout(Duration.ofMillis(400)),
                      HttpFetchObserver.NONE,
                      fetcher));
      assertTrue(started.await(2, TimeUnit.SECONDS));
      var second = pool.submit(() -> get(cache, uri, new AtomicBoolean()));
      await(() -> cache.waiters() == 2);
      assertEquals(
          HttpFetchException.Code.TOTAL_TIMEOUT,
          ((HttpFetchException)
                  assertThrows(ExecutionException.class, () -> first.get(2, TimeUnit.SECONDS))
                      .getCause())
              .code());
      assertEquals(1, stopped.getCount());
      release.set(true);
      assertEquals(
          "evidence 1", new String(second.get(2, TimeUnit.SECONDS).body(), StandardCharsets.UTF_8));
      assertEquals(1, calls.get());
    }
  }

  /** 取得した情報の再利用で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void slowerEarlierLoadCannotOverwriteAnExplicitRefresh() throws Exception {
    loader(false, false);
    try (var cache = cache(new HttpCacheProperties());
        var pool = Executors.newSingleThreadExecutor()) {
      var earlier = pool.submit(() -> get(cache, uri, new AtomicBoolean()));
      assertTrue(started.await(2, TimeUnit.SECONDS));
      var refresh =
          new HttpResponseCache.Request(
              uri, Map.of(), request(uri).policy(), HttpResponseCache.Namespace.PAGE, true);
      assertEquals(
          "evidence 2",
          new String(
              cache.fetch(refresh, FetchOperation.active(), HttpFetchObserver.NONE, fetcher).body(),
              StandardCharsets.UTF_8));
      release.set(true);
      assertEquals(
          "evidence 1",
          new String(earlier.get(2, TimeUnit.SECONDS).body(), StandardCharsets.UTF_8));
      assertEquals(
          "evidence 2",
          new String(get(cache, uri, new AtomicBoolean()).body(), StandardCharsets.UTF_8));
      assertEquals(2, calls.get());
    }
  }

  /** 取得した情報の再利用で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void ordinaryColdLoadStartingDuringRefreshCannotReplaceRefreshResult() throws Exception {
    loader(false, false);
    try (var cache = cache(new HttpCacheProperties());
        var pool = Executors.newSingleThreadExecutor()) {
      var refresh =
          new HttpResponseCache.Request(
              uri, Map.of(), request(uri).policy(), HttpResponseCache.Namespace.PAGE, true);
      var refreshing =
          pool.submit(
              () -> cache.fetch(refresh, FetchOperation.active(), HttpFetchObserver.NONE, fetcher));
      assertTrue(started.await(2, TimeUnit.SECONDS));
      assertEquals(
          "evidence 2",
          new String(get(cache, uri, new AtomicBoolean()).body(), StandardCharsets.UTF_8));
      release.set(true);
      assertEquals(
          "evidence 1",
          new String(refreshing.get(2, TimeUnit.SECONDS).body(), StandardCharsets.UTF_8));
      assertEquals(
          "evidence 1",
          new String(get(cache, uri, new AtomicBoolean()).body(), StandardCharsets.UTF_8));
      assertEquals(2, calls.get());
    }
  }

  /** 取得した情報の再利用で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void joinedWaiterCanUseItsOwnQuotaWhenFirstOwnersQuotaIsExhausted() throws Exception {
    doAnswer(
            invocation -> {
              int call = calls.incrementAndGet();
              var operation = (FetchOperation) invocation.getArgument(3);
              started.countDown();
              if (call == 1)
                while (!release.get()) {
                  operation.check();
                  Thread.sleep(10);
                }
              ((HttpFetchObserver) invocation.getArgument(4)).request(false);
              return new SafeHttpFetcher.Response(
                  200, Map.of(), "evidence".getBytes(StandardCharsets.UTF_8), uri);
            })
        .when(fetcher)
        .fetch(any(), anyMap(), any(), any(), any());
    var exhausted =
        new HttpFetchObserver() {
          /** 検証で使用する要求を、指定された条件に合わせて用意する。 */
          public void request(boolean redirect) {
            throw new HttpFetchException(HttpFetchException.Code.REQUEST_BUDGET);
          }
        };
    try (var cache = cache(new HttpCacheProperties());
        var pool = Executors.newFixedThreadPool(2)) {
      var first =
          pool.submit(() -> cache.fetch(request(uri), FetchOperation.active(), exhausted, fetcher));
      assertTrue(started.await(2, TimeUnit.SECONDS));
      var second = pool.submit(() -> get(cache, uri, new AtomicBoolean()));
      await(() -> cache.waiters() == 2);
      release.set(true);
      assertThrows(ExecutionException.class, () -> first.get(2, TimeUnit.SECONDS));
      assertEquals(
          "evidence", new String(second.get(2, TimeUnit.SECONDS).body(), StandardCharsets.UTF_8));
      assertEquals(2, calls.get());
      assertEquals(0, cache.inFlight());
    }
  }

  /** 取得した情報の再利用の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void cancelledOldLoadCannotRemoveANewerSuccessfulCacheEntry() throws Exception {
    var finishOld = new CountDownLatch(1);
    var cancel = new AtomicBoolean();
    doAnswer(
            invocation -> {
              int call = calls.incrementAndGet();
              started.countDown();
              if (call == 1) {
                boolean finished = false;
                while (!finished)
                  try {
                    finished = finishOld.await(2, TimeUnit.SECONDS);
                  } catch (InterruptedException stopping) {
                    /* Delay exit to exercise cancellation versus replacement. */
                  }
              }
              return new SafeHttpFetcher.Response(
                  200, Map.of(), ("evidence " + call).getBytes(StandardCharsets.UTF_8), uri);
            })
        .when(fetcher)
        .fetch(any(), anyMap(), any(), any(), any());
    try (var cache = cache(new HttpCacheProperties());
        var pool = Executors.newSingleThreadExecutor()) {
      try {
        var old = pool.submit(() -> get(cache, uri, cancel));
        assertTrue(started.await(2, TimeUnit.SECONDS));
        cancel.set(true);
        assertThrows(ExecutionException.class, () -> old.get(2, TimeUnit.SECONDS));
        assertEquals(0, cache.inFlight());
        assertEquals(
            "evidence 2",
            new String(get(cache, uri, new AtomicBoolean()).body(), StandardCharsets.UTF_8));
        finishOld.countDown();
        await(() -> cache.activeLoads() == 0);
        assertEquals(
            "evidence 2",
            new String(get(cache, uri, new AtomicBoolean()).body(), StandardCharsets.UTF_8));
        assertEquals(2, calls.get());
      } finally {
        finishOld.countDown();
      }
    }
  }
}
