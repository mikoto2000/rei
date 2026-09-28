package dev.mikoto2000.rei.paper;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PaperHttpPolicyTest {
  @Test
  void wrappedConnectionFailureRetries() {
    var calls = new AtomicInteger();
    var client =
        new SafePaperHttpClient(config()) {
          @Override
          protected Response exchange(URI u, String t, int max, PaperOperation op) {
            if (calls.incrementAndGet() == 1)
              throw new PaperException(
                  PaperException.Code.SEARCH_FAILED,
                  "io",
                  new java.util.concurrent.ExecutionException(new java.io.IOException()));
            return new Response(200, null, "", new byte[] {1});
          }
        };
    assertArrayEquals(
        new byte[] {1},
        client.get(
            URI.create("https://example.org"), "application/pdf", 100, PaperOperation.local("s")));
    assertEquals(2, calls.get());
  }

  PaperProperties config() {
    var p = new PaperProperties();
    p.setBackoff(Duration.ZERO);
    return p;
  }

  @Test
  void retriesRateLimitBoundedly() {
    var calls = new AtomicInteger();
    var client =
        new SafePaperHttpClient(config()) {
          @Override
          protected Response exchange(URI u, String t, int max, PaperOperation op) {
            calls.incrementAndGet();
            return new Response(429, null, "0", new byte[0]);
          }
        };
    var e =
        assertThrows(
            PaperException.class,
            () ->
                client.get(
                    URI.create("https://example.org"),
                    "application/pdf",
                    100,
                    PaperOperation.local("s")));
    assertEquals(PaperException.Code.PROVIDER_RATE_LIMITED, e.code());
    assertEquals(3, calls.get());
  }

  @Test
  void recoversFromServerError() {
    var calls = new AtomicInteger();
    var client =
        new SafePaperHttpClient(config()) {
          @Override
          protected Response exchange(URI u, String t, int max, PaperOperation op) {
            return calls.incrementAndGet() == 1
                ? new Response(503, null, "0", new byte[0])
                : new Response(200, null, "", new byte[] {42});
          }
        };
    assertArrayEquals(
        new byte[] {42},
        client.get(
            URI.create("https://example.org"), "application/pdf", 100, PaperOperation.local("s")));
    assertEquals(2, calls.get());
  }

  @Test
  void redirectTargetIsRevalidated() {
    var calls = new AtomicInteger();
    var client =
        new SafePaperHttpClient(config()) {
          @Override
          protected Response exchange(URI u, String t, int max, PaperOperation op) {
            calls.incrementAndGet();
            return new Response(302, "http://127.0.0.1/secret", "", new byte[0]);
          }
        };
    assertThrows(
        PaperException.class,
        () ->
            client.get(
                URI.create("https://example.org"),
                "application/pdf",
                100,
                PaperOperation.local("s")));
    assertEquals(1, calls.get());
  }

  @Test
  void redirectLoopsAreBounded() {
    var calls = new AtomicInteger();
    var client =
        new SafePaperHttpClient(config()) {
          @Override
          protected Response exchange(URI u, String t, int max, PaperOperation op) {
            calls.incrementAndGet();
            return new Response(302, "/again", "", new byte[0]);
          }
        };
    assertThrows(
        PaperException.class,
        () ->
            client.get(
                URI.create("https://example.org"),
                "application/pdf",
                100,
                PaperOperation.local("s")));
    assertEquals(6, calls.get());
  }

  @Test
  void notFoundDoesNotRetry() {
    var calls = new AtomicInteger();
    var client =
        new SafePaperHttpClient(config()) {
          @Override
          protected Response exchange(URI u, String t, int max, PaperOperation op) {
            calls.incrementAndGet();
            return new Response(404, null, "", new byte[0]);
          }
        };
    assertThrows(
        PaperException.class,
        () ->
            client.get(
                URI.create("https://example.org"),
                "application/pdf",
                100,
                PaperOperation.local("s")));
    assertEquals(1, calls.get());
  }
}
