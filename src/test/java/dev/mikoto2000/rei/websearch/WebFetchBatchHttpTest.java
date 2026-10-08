package dev.mikoto2000.rei.websearch;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.net.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import dev.mikoto2000.rei.http.*;

class WebFetchBatchHttpTest {
  @Test void realHttpRequestsRespectHostLimitAndKeepSuccessBesideTimeout() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var workers = Executors.newFixedThreadPool(3); server.setExecutor(workers); server.start();
    var live = new AtomicInteger(); var maximum = new AtomicInteger();
    server.createContext("/", exchange -> {
      maximum.accumulateAndGet(live.incrementAndGet(), Math::max);
      try {
        Thread.sleep(exchange.getRequestURI().getPath().endsWith("0") ? 1000 : 60);
        exchange.sendResponseHeaders(200, 2); exchange.getResponseBody().write(new byte[] { 'o', 'k' });
      } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
      finally { live.decrementAndGet(); exchange.close(); }
    });
    URI origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    var policy = new HttpFetchPolicy(1024, 1024, Duration.ofSeconds(1), Duration.ofSeconds(2),
        Duration.ofSeconds(3), 0, null, origin, false);
    // Warm the real transport before applying the deliberately short batch deadline.
    new SafeHttpFetcher().fetch(origin.resolve("/warm"), Map.of(), policy, FetchOperation.active(), HttpFetchObserver.NONE);
    var input = new ArrayList<WebSearchSelection.Candidate>();
    for (int i = 0; i < 3; i++) input.add(new WebSearchSelection.Candidate(
        new WebSearchResult("page", "https://source" + i + ".example/", "", null), List.of(), i));
    try (var batch = new WebFetchBatch(3, 2, 32)) {
      var result = batch.fetch(input, Duration.ofMillis(600), candidate -> new SafeHttpFetcher().fetch(
          origin.resolve("/" + candidate.rank()), Map.of(), policy, FetchScope.current(), HttpFetchObserver.NONE));
      assertEquals(WebFetchBatch.Status.TIMEOUT, result.getFirst().status());
      assertTrue(result.get(1).success()); assertTrue(result.get(2).success());
      assertTrue(maximum.get() <= 2); assertEquals(0, batch.activeTasks());
    } finally { server.stop(0); workers.shutdownNow(); workers.awaitTermination(2, TimeUnit.SECONDS); }
  }
  @Test void batchDeadlineClosesAnActualInFlightSocket() throws Exception {
    try (var listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        var server = Executors.newSingleThreadExecutor(); var batch = new WebFetchBatch(1, 1, 32)) {
      var closed = new CountDownLatch(1); var accepted = new CountDownLatch(1);
      var peer = server.submit(() -> {
        try (var socket = listener.accept()) {
          socket.setSoTimeout(2000); var reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
          for (String line; (line = reader.readLine()) != null && !line.isEmpty();) { }
          socket.getOutputStream().write("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n2\r\nok\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
          socket.getOutputStream().flush(); accepted.countDown();
          if (socket.getInputStream().read() == -1) closed.countDown();
        } catch (java.net.SocketException reset) { closed.countDown(); }
        return null;
      });
      URI origin = URI.create("http://127.0.0.1:" + listener.getLocalPort());
      var policy = new HttpFetchPolicy(1024, 1024, Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(3), 0, null, origin, false);
      var input = List.of(new WebSearchSelection.Candidate(new WebSearchResult("page", origin.toString(), "", null), List.of(), 0));
      var result = batch.fetch(input, Duration.ofMillis(600), candidate -> new SafeHttpFetcher().fetch(
          origin, Map.of(), policy, FetchScope.current(), HttpFetchObserver.NONE));
      assertEquals(0, accepted.getCount()); assertEquals(WebFetchBatch.Status.TIMEOUT, result.getFirst().status());
      assertTrue(closed.await(1, TimeUnit.SECONDS)); peer.get(1, TimeUnit.SECONDS);
      assertEquals(0, batch.activeTasks()); assertEquals(0, batch.hostEntries());
    }
  }
}
