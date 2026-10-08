package dev.mikoto2000.rei.core.searchcache;

import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import dev.mikoto2000.rei.testsupport.AdjustableClock;

class BoundedTtlCacheTest {
  AdjustableClock clock = new AdjustableClock(Instant.parse("2026-01-01T00:00:00Z"));
  BoundedTtlCache<String, String> cache(int entries, long bytes) {
    return new BoundedTtlCache<>(Duration.ofSeconds(60), entries, bytes, clock, String::length);
  }
  @Test void ttlExpiresAtTheBoundaryOnTheSameCacheInstance() {
    var cache = cache(10, 100); assertTrue(cache.put("a", "evidence"));
    clock.advance(Duration.ofSeconds(59)); assertEquals("evidence", cache.get("a").orElseThrow());
    clock.advance(Duration.ofSeconds(1)); assertTrue(cache.get("a").isEmpty());
    assertEquals(0, cache.size()); assertEquals(0, cache.bytes());
  }
  @Test void entryAndByteCapsEvictOldestAndRejectOversizedValues() {
    var cache = cache(2, 6); cache.put("a", "aaa"); cache.put("b", "bbb"); cache.put("c", "ccc");
    assertTrue(cache.get("a").isEmpty()); assertEquals(2, cache.size()); assertEquals(6, cache.bytes());
    assertFalse(cache.put("huge", "xxxxxxx")); assertEquals(2, cache.size());
    cache.put("b", "b"); assertEquals(4, cache.bytes()); cache.remove("b"); assertEquals(3, cache.bytes());
    cache.clear(); assertEquals(0, cache.bytes());
  }
  @Test void concurrentUpdatesStayWithinBothCaps() throws Exception {
    var cache = cache(10, 40);
    try (var pool = Executors.newFixedThreadPool(6)) {
      var tasks = new java.util.ArrayList<Future<?>>();
      for (int thread = 0; thread < 6; thread++) {
        int number = thread;
        tasks.add(pool.submit(() -> { for (int i = 0; i < 100; i++) { String key = number + ":" + i;
          cache.put(key, "data"); cache.get(key); assertTrue(cache.size() <= 10); assertTrue(cache.bytes() <= 40);
        } }));
      }
      for (var task : tasks) task.get();
    }
    assertTrue(cache.size() <= 10); assertTrue(cache.bytes() <= 40);
  }
  @Test void byteArithmeticCannotOverflow() {
    var cache = new BoundedTtlCache<String, Long>(Duration.ofSeconds(60), 10, Long.MAX_VALUE, clock, value -> value);
    cache.put("a", Long.MAX_VALUE); cache.put("b", 1L);
    assertTrue(cache.get("a").isEmpty()); assertEquals(1, cache.bytes());
  }
  @Test void invalidLimitsAndWeightsAreRejected() {
    assertThrows(IllegalArgumentException.class, () -> cache(0, 100));
    assertThrows(IllegalArgumentException.class, () -> cache(10, 0));
    var cache = new BoundedTtlCache<String, String>(Duration.ofSeconds(1), 1, 10, clock, value -> -1L);
    assertThrows(IllegalArgumentException.class, () -> cache.put("a", "data")); assertEquals(0, cache.size());
  }
}
