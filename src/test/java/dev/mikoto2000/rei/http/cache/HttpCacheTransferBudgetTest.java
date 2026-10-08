package dev.mikoto2000.rei.http.cache;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import dev.mikoto2000.rei.http.*;
import dev.mikoto2000.rei.memory.util.SensitiveInfoDetector;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class HttpCacheTransferBudgetTest {
  @Test void revalidated304ChargesTheReusedDecodedBody() {
    var calls=new AtomicInteger();
    doAnswer(invocation -> calls.incrementAndGet()==1
        ?new SafeHttpFetcher.Response(200,Map.of("etag","\"a\""),"data".getBytes(),uri)
        :new SafeHttpFetcher.Response(304,Map.of("etag","\"a\""),new byte[0],uri))
        .when(raw).fetch(any(),anyMap(),any(),any(),any());
    try(var cache=cache()) {
      get(cache);
      var budget=new TransferBudget(10,3);
      try(var scope=TransferScope.enter(budget)) {
        var forced=new HttpResponseCache.Request(uri,Map.of(),request.policy(),HttpResponseCache.Namespace.PAGE,true);
        assertEquals(HttpFetchException.Code.TRANSFER_BUDGET,
            assertThrows(HttpFetchException.class,()->cache.fetch(forced,FetchOperation.active(),HttpFetchObserver.NONE,raw)).code());
      }
      assertEquals(2,calls.get());assertEquals(0,budget.wireBytes());assertEquals(0,budget.decodedBytes());
    }
  }
  final URI uri = URI.create("https://example.com/page");
  final SafeHttpFetcher raw = mock(SafeHttpFetcher.class);
  final HttpResponseCache.Request request = new HttpResponseCache.Request(uri, Map.of(), HttpFetchPolicy.html(Duration.ofSeconds(3)), HttpResponseCache.Namespace.PAGE, false);
  HttpResponseCache cache() { return new HttpResponseCache(new HttpCacheProperties(), Clock.systemUTC(), new SensitiveInfoDetector()); }
  SafeHttpFetcher.Response get(HttpResponseCache cache) { return cache.fetch(request, FetchOperation.active(), HttpFetchObserver.NONE, raw); }
  @Test void cacheHitConsumesDecodedVolumeWithoutChargingNewWireTraffic() {
    var calls = new AtomicInteger();
    doAnswer(invocation -> {
      calls.incrementAndGet(); TransferScope.wire(3); TransferScope.decoded(4);
      return new SafeHttpFetcher.Response(200, Map.of(), "data".getBytes(), uri);
    }).when(raw).fetch(any(), anyMap(), any(), any(), any());
    var budget = new TransferBudget(10, 7);
    try (var cache = cache(); var scope = TransferScope.enter(budget)) {
      get(cache); assertEquals(3, budget.wireBytes()); assertEquals(4, budget.decodedBytes());
      assertEquals(HttpFetchException.Code.TRANSFER_BUDGET, assertThrows(HttpFetchException.class, () -> get(cache)).code());
      assertEquals(1, calls.get()); assertEquals(3, budget.wireBytes());
    }
  }
  @Test void differentAggregateBudgetsCannotStopEachOthersColdLoad() throws Exception {
    var started = new CountDownLatch(2); var release = new CountDownLatch(1); var calls = new AtomicInteger();
    doAnswer(invocation -> {
      int call = calls.incrementAndGet(); started.countDown();
      if (call == 1) assertTrue(release.await(2, TimeUnit.SECONDS));
      TransferScope.wire(2); TransferScope.decoded(4);
      return new SafeHttpFetcher.Response(200, Map.of(), "data".getBytes(), uri);
    }).when(raw).fetch(any(), anyMap(), any(), any(), any());
    try (var cache = cache(); var pool = Executors.newFixedThreadPool(2)) {
      try {
        var first = pool.submit(() -> { try (var scope = TransferScope.enter(new TransferBudget(1, 10))) { return get(cache); } });
        long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
        while (calls.get() == 0 && System.nanoTime() < deadline) Thread.sleep(5);
        assertEquals(1, calls.get());
        var second = pool.submit(() -> { try (var scope = TransferScope.enter(new TransferBudget(10, 10))) { return get(cache); } });
        assertTrue(started.await(1, TimeUnit.SECONDS)); release.countDown();
        assertEquals(HttpFetchException.Code.TRANSFER_BUDGET, ((HttpFetchException) assertThrows(ExecutionException.class, () -> first.get(2, TimeUnit.SECONDS)).getCause()).code());
        assertEquals("data", new String(second.get(2, TimeUnit.SECONDS).body())); assertEquals(2, calls.get());
      } finally { release.countDown(); }
    }
  }
}
