package dev.mikoto2000.rei.urlfetch;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import dev.mikoto2000.rei.http.*;
import dev.mikoto2000.rei.http.cache.*;
import dev.mikoto2000.rei.memory.util.SensitiveInfoDetector;
import dev.mikoto2000.rei.testsupport.AdjustableClock;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class UrlContentCacheIntegrationTest {
  @Test void managedBodyPathReusesAndRevalidatesWithoutLosingFetchTime() {
    var clock = new AdjustableClock(Instant.parse("2026-01-01T00:00:00Z"));
    var raw = mock(SafeHttpFetcher.class); var calls = new AtomicInteger();
    doAnswer(invocation -> {
      var uri = (java.net.URI) invocation.getArgument(0);
      if (calls.incrementAndGet() == 1)
        return new SafeHttpFetcher.Response(200, Map.of("etag", "\"a\"", "content-type", "text/plain"), "evidence".getBytes(), uri);
      Map<String, String> headers = invocation.getArgument(1);
      assertEquals("\"a\"", headers.get("if-none-match"));
      assertTrue(headers.get("cache-control").contains("no-cache"));
      return new SafeHttpFetcher.Response(304, Map.of("etag", "\"a\""), new byte[0], uri);
    }).when(raw).fetch(any(), anyMap(), any(), any(), any());
    try (var cache = new HttpResponseCache(new HttpCacheProperties(), clock, new SensitiveInfoDetector())) {
      var service = new UrlContentFetchService(new UrlValidator(), new UrlFetchProperties(), raw, cache);
      var first = service.fetch("https://example.com/page");
      assertTrue(first.success()); assertEquals("evidence", service.fetch("https://example.com/page").content());
      assertEquals(1, calls.get()); clock.advance(Duration.ofSeconds(5));
      try (var refresh = FetchScope.withForceRefresh(true)) {
        var updated = service.fetch("https://example.com/page");
        assertEquals(first.retrievedAt(), updated.retrievedAt()); assertEquals(clock.instant(), updated.validatedAt());
      }
      assertEquals(2, calls.get());
    }
  }
}
