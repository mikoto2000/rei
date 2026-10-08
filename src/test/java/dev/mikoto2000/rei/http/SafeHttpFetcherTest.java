package dev.mikoto2000.rei.http;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.*;

class SafeHttpFetcherTest {
  @Test void repeatedCacheControlDoesNotLoseAnEarlierNoStoreDirective() {
    server.createContext("/cache-control", exchange -> {
      exchange.getResponseHeaders().add("Cache-Control", "no-store");
      exchange.getResponseHeaders().add("Cache-Control", "max-age=60");
      exchange.sendResponseHeaders(200, 2); exchange.getResponseBody().write(new byte[] { 'o', 'k' }); exchange.close();
    });
    String control = get("/cache-control", policy(128, 128, 1, 2000, 1000)).header("cache-control");
    assertTrue(control.contains("no-store")); assertTrue(control.contains("max-age=60"));
  }
  @Test void crossOriginRedirectKeepsRefreshDirectiveButDoesNotForwardValidators() {
    URI initial = URI.create("https://first.example/page"), destination = URI.create("https://second.example/page");
    var forwarded = new java.util.concurrent.atomic.AtomicReference<Map<String, String>>();
    var fetcher = new SafeHttpFetcher() {
      @Override public Response exchange(URI uri, Map<String, String> headers, HttpFetchPolicy policy, FetchOperation operation, HttpFetchObserver observer, boolean redirect) {
        if (uri.equals(initial)) return new Response(302, Map.of("location", destination.toString()), new byte[0], uri);
        forwarded.set(headers); return new Response(200, Map.of(), new byte[] { 'o', 'k' }, uri);
      }
    };
    var result = fetcher.fetch(initial, Map.of("cache-control", "no-cache", "if-none-match", "\"a\"", "if-modified-since", "Wed, 31 Dec 2025 00:00:00 GMT"),
        HttpFetchPolicy.html(Duration.ofSeconds(2)), FetchOperation.active(), HttpFetchObserver.NONE);
    assertEquals(destination, result.finalUri()); assertEquals("no-cache", forwarded.get().get("cache-control"));
    assertFalse(forwarded.get().containsKey("if-none-match")); assertFalse(forwarded.get().containsKey("if-modified-since"));
    assertThrows(HttpFetchException.class, () -> fetcher.fetch(initial, Map.of("authorization", "Bearer private"),
        HttpFetchPolicy.html(Duration.ofSeconds(2)), FetchOperation.active(), HttpFetchObserver.NONE));
  }
  @Test void actualPeerMustBeBothApprovedAndAllowed() throws Exception {
    var policy = HttpFetchPolicy.html(Duration.ofSeconds(1));
    var publicIp = InetAddress.getByName("1.1.1.1"); var privateIp = InetAddress.getByName("127.0.0.1");
    assertDoesNotThrow(() -> SafeHttpFetcher.validatePeer(new InetSocketAddress(publicIp, 443), Set.of(publicIp), policy));
    assertThrows(HttpFetchException.class, () -> SafeHttpFetcher.validatePeer(new InetSocketAddress(publicIp, 443), Set.of(), policy));
    assertThrows(HttpFetchException.class, () -> SafeHttpFetcher.validatePeer(new InetSocketAddress(privateIp, 443), Set.of(privateIp), policy));
    assertThrows(HttpFetchException.class, () -> SafeHttpFetcher.validatePeer(InetSocketAddress.createUnresolved("fixture.example", 443), Set.of(publicIp), policy));
  }
  HttpServer server; ExecutorService executor; URI origin;
  @BeforeEach void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    executor = Executors.newCachedThreadPool(); server.setExecutor(executor); server.start();
    origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
  }
  @AfterEach void stop() { server.stop(0); executor.shutdownNow(); }
  HttpFetchPolicy policy(int wire, int decoded, int redirects, long total, long read) {
    return new HttpFetchPolicy(wire, decoded, Duration.ofSeconds(1), Duration.ofMillis(read),
        Duration.ofMillis(total), redirects, null, origin, false);
  }
  SafeHttpFetcher.Response get(String path, HttpFetchPolicy policy) {
    return new SafeHttpFetcher().fetch(origin.resolve(path), Map.of(), policy, FetchOperation.active(), HttpFetchObserver.NONE);
  }
  @Test void chunkedBodyHasNoContentLengthAndStillHonorsWireLimit() {
    server.createContext("/body", exchange -> {
      exchange.sendResponseHeaders(200, 0);
      try (var out = exchange.getResponseBody()) { out.write(new byte[65]); }
    });
    assertEquals(65, get("/body", policy(65, 65, 2, 2000, 1000)).body().length);
    assertEquals(HttpFetchException.Code.WIRE_LIMIT,
        assertThrows(HttpFetchException.class, () -> get("/body", policy(64, 100, 2, 2000, 1000))).code());
  }
  @Test void compressedWireAndExpandedBodyHaveSeparateLimits() throws Exception {
    var bytes = new ByteArrayOutputStream();
    try (var gzip = new GZIPOutputStream(bytes)) { gzip.write(new byte[4096]); }
    server.createContext("/gzip", exchange -> {
      exchange.getResponseHeaders().set("Content-Encoding", "gzip"); exchange.sendResponseHeaders(200, bytes.size());
      try (var out = exchange.getResponseBody()) { out.write(bytes.toByteArray()); }
    });
    assertEquals(4096, get("/gzip", policy(128, 4096, 2, 2000, 1000)).body().length);
    assertEquals(HttpFetchException.Code.DECODED_LIMIT,
        assertThrows(HttpFetchException.class, () -> get("/gzip", policy(128, 64, 2, 2000, 1000))).code());
  }
  @Test void redirectsShareDeadlineAndCannotLeaveConfiguredOrigin() {
    server.createContext("/redirect", exchange -> {
      exchange.getResponseHeaders().set("Location", "http://169.254.169.254/latest/meta-data");
      exchange.sendResponseHeaders(302, -1); exchange.close();
    });
    assertEquals(HttpFetchException.Code.URL_NOT_ALLOWED,
        assertThrows(HttpFetchException.class, () -> get("/redirect", policy(128, 128, 2, 2000, 1000))).code());
    server.createContext("/loop", exchange -> {
      exchange.getResponseHeaders().set("Location", "/loop"); exchange.sendResponseHeaders(302, -1); exchange.close();
    });
    var requests = new AtomicInteger();
    assertEquals(HttpFetchException.Code.REDIRECT_LIMIT, assertThrows(HttpFetchException.class, () ->
        new SafeHttpFetcher().fetch(origin.resolve("/loop"), Map.of(), policy(128, 128, 2, 2000, 1000),
            FetchOperation.active(), new HttpFetchObserver() { public void request(boolean redirect) { requests.incrementAndGet(); } })).code());
    assertEquals(3, requests.get());
  }
  @Test void slowResponseHasReadTimeoutAndWholeOperationTimeout() {
    server.createContext("/slow", exchange -> {
      try { Thread.sleep(500); exchange.sendResponseHeaders(200, -1); }
      catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
      finally { exchange.close(); }
    });
    assertEquals(HttpFetchException.Code.READ_TIMEOUT,
        assertThrows(HttpFetchException.class, () -> get("/slow", policy(128, 128, 2, 2000, 50))).code());
    assertEquals(HttpFetchException.Code.TOTAL_TIMEOUT,
        assertThrows(HttpFetchException.class, () -> get("/slow", policy(128, 128, 2, 50, 1000))).code());
  }
  @Test void cancellationIsCheckedBeforeRequestAndPublicPageCannotUseTestOrigin() {
    var requests = new AtomicInteger();
    var observer = new HttpFetchObserver() { public void request(boolean redirect) { requests.incrementAndGet(); } };
    assertThrows(CancellationException.class, () -> new SafeHttpFetcher().fetch(origin, Map.of(),
        policy(128, 128, 2, 2000, 1000), new FetchOperation(() -> { throw new CancellationException(); }, Long.MAX_VALUE), observer));
    assertEquals(0, requests.get());
    assertThrows(HttpFetchException.class, () -> new SafeHttpFetcher().fetch(origin, Map.of(),
        HttpFetchPolicy.html(Duration.ofSeconds(1)), FetchOperation.active(), observer));
    assertEquals(0, requests.get());
  }
  @Test void cancellingInFlightClosesTheConnection() throws Exception {
    var started = new CountDownLatch(1); var disconnected = new CountDownLatch(1);
    var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
    server.createContext("/stream", exchange -> {
      exchange.sendResponseHeaders(200, 0); started.countDown();
      try (var out = exchange.getResponseBody()) {
        for (int i = 0; i < 100; i++) {
          out.write(new byte[8192]); out.flush(); Thread.sleep(10);
        }
      } catch (IOException closed) { disconnected.countDown(); }
      catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
      finally { exchange.close(); }
    });
    try (var waiter = Executors.newSingleThreadExecutor()) {
      var future = waiter.submit(() -> new SafeHttpFetcher().fetch(origin.resolve("/stream"), Map.of(),
          policy(1_000_000, 1_000_000, 2, 5000, 1000),
          new FetchOperation(() -> { if (cancelled.get()) throw new CancellationException(); }, Long.MAX_VALUE), HttpFetchObserver.NONE));
      assertTrue(started.await(2, TimeUnit.SECONDS)); cancelled.set(true);
      assertInstanceOf(CancellationException.class, assertThrows(ExecutionException.class,
          () -> future.get(2, TimeUnit.SECONDS)).getCause());
      assertTrue(disconnected.await(2, TimeUnit.SECONDS));
    }
  }
  @Test void conflictingContentLengthAndChunkedHeadersAreRejected() throws Exception {
    try (var socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
         var writer = Executors.newSingleThreadExecutor()) {
      var served = writer.submit(() -> {
        try (var peer = socket.accept()) {
          var input = new BufferedReader(new InputStreamReader(peer.getInputStream(), java.nio.charset.StandardCharsets.US_ASCII));
          while (!input.readLine().isEmpty()) {}
          peer.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: 1\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n41\r\n"
              + "x".repeat(65) + "\r\n0\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
          peer.getOutputStream().flush();
        } catch (IOException failed) { throw new UncheckedIOException(failed); }
      });
      URI fixture = URI.create("http://127.0.0.1:" + socket.getLocalPort());
      var policy = new HttpFetchPolicy(64, 128, Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(2), 2, null, fixture, false);
      // Netty rejects this ambiguous HTTP framing before exposing a body.
      assertEquals(HttpFetchException.Code.NETWORK_ERROR, assertThrows(HttpFetchException.class, () ->
          new SafeHttpFetcher().fetch(fixture, Map.of(), policy, FetchOperation.active(), HttpFetchObserver.NONE)).code());
      served.get(2, TimeUnit.SECONDS);
    }
  }
  @Test void oversizedDeclaredLengthIsRejectedWithoutWaitingForTheDeclaredBody() {
    server.createContext("/declared", exchange -> {
      exchange.sendResponseHeaders(200, 65);
      try (var out = exchange.getResponseBody()) { out.write(1); out.flush(); }
      catch (IOException incompleteFixtureBody) { exchange.close(); }
    });
    assertEquals(HttpFetchException.Code.WIRE_LIMIT, assertThrows(HttpFetchException.class,
        () -> get("/declared", policy(64, 128, 2, 2000, 1000))).code());
  }
}
