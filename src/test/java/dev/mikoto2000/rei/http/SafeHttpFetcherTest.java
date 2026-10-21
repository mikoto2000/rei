package dev.mikoto2000.rei.http;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 安全性HTTP通信情報の取得元の結果と状態の契約を検証するテスト。 */
class SafeHttpFetcherTest {
  /** 外部情報の取得で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void aggregateBudgetIncludesMultipleActualResponsesAndCompressedExpansion() throws Exception {
    var bytes = new ByteArrayOutputStream();
    try (var gzip = new GZIPOutputStream(bytes)) {
      gzip.write(new byte[4096]);
    }
    server.createContext(
        "/aggregate",
        exchange -> {
          exchange.getResponseHeaders().set("Content-Encoding", "gzip");
          exchange.sendResponseHeaders(200, bytes.size());
          try (var output = exchange.getResponseBody()) {
            output.write(bytes.toByteArray());
          }
        });
    var budget = new TransferBudget(1000, 5000);
    try (var scope = TransferScope.enter(budget)) {
      assertEquals(4096, get("/aggregate", policy(128, 5000, 2, 2000, 1000)).body().length);
      assertEquals(
          HttpFetchException.Code.TRANSFER_BUDGET,
          assertThrows(
                  HttpFetchException.class,
                  () -> get("/aggregate", policy(128, 5000, 2, 2000, 1000)))
              .code());
    }
    assertEquals(bytes.size() * 2, budget.wireBytes());
    assertEquals(4096, budget.decodedBytes());
  }

  /** 外部情報の取得の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void aggregateWireLimitCancelsAnActualStreamingConnection() throws Exception {
    var disconnected = new CountDownLatch(1);
    server.createContext(
        "/aggregate-stream",
        exchange -> {
          exchange.sendResponseHeaders(200, 0);
          try (var output = exchange.getResponseBody()) {
            for (int i = 0; i < 100; i++) {
              output.write(new byte[8192]);
              output.flush();
              Thread.sleep(10);
            }
          } catch (IOException closed) {
            disconnected.countDown();
          } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
          } finally {
            exchange.close();
          }
        });
    var budget = new TransferBudget(1000, 100000);
    try (var scope = TransferScope.enter(budget)) {
      assertEquals(
          HttpFetchException.Code.TRANSFER_BUDGET,
          assertThrows(
                  HttpFetchException.class,
                  () -> get("/aggregate-stream", policy(1000000, 1000000, 2, 5000, 1000)))
              .code());
    }
    assertTrue(disconnected.await(2, TimeUnit.SECONDS));
    assertTrue(budget.wireBytes() <= 1000);
  }

  /** 外部情報の取得で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void repeatedCacheControlDoesNotLoseAnEarlierNoStoreDirective() {
    server.createContext(
        "/cache-control",
        exchange -> {
          exchange.getResponseHeaders().add("Cache-Control", "no-store");
          exchange.getResponseHeaders().add("Cache-Control", "max-age=60");
          exchange.sendResponseHeaders(200, 2);
          exchange.getResponseBody().write(new byte[] {'o', 'k'});
          exchange.close();
        });
    String control = get("/cache-control", policy(128, 128, 1, 2000, 1000)).header("cache-control");
    assertTrue(control.contains("no-store"));
    assertTrue(control.contains("max-age=60"));
  }

  /** 外部情報の取得で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void crossOriginRedirectKeepsRefreshDirectiveButDoesNotForwardValidators() {
    URI initial = URI.create("https://first.example/page"),
        destination = URI.create("https://second.example/page");
    var forwarded = new java.util.concurrent.atomic.AtomicReference<Map<String, String>>();
    var fetcher =
        new SafeHttpFetcher() {
          /** {@code exchange} の条件を再現するための補助情報を返す。 */
          @Override
          public Response exchange(
              URI uri,
              Map<String, String> headers,
              HttpFetchPolicy policy,
              FetchOperation operation,
              HttpFetchObserver observer,
              boolean redirect) {
            if (uri.equals(initial))
              return new Response(
                  302, Map.of("location", destination.toString()), new byte[0], uri);
            forwarded.set(headers);
            return new Response(200, Map.of(), new byte[] {'o', 'k'}, uri);
          }
        };
    var result =
        fetcher.fetch(
            initial,
            Map.of(
                "cache-control",
                "no-cache",
                "if-none-match",
                "\"a\"",
                "if-modified-since",
                "Wed, 31 Dec 2025 00:00:00 GMT"),
            HttpFetchPolicy.html(Duration.ofSeconds(2)),
            FetchOperation.active(),
            HttpFetchObserver.NONE);
    assertEquals(destination, result.finalUri());
    assertEquals("no-cache", forwarded.get().get("cache-control"));
    assertFalse(forwarded.get().containsKey("if-none-match"));
    assertFalse(forwarded.get().containsKey("if-modified-since"));
    assertThrows(
        HttpFetchException.class,
        () ->
            fetcher.fetch(
                initial,
                Map.of("authorization", "Bearer private"),
                HttpFetchPolicy.html(Duration.ofSeconds(2)),
                FetchOperation.active(),
                HttpFetchObserver.NONE));
  }

  /** 外部情報の取得で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void actualPeerMustBeBothApprovedAndAllowed() throws Exception {
    var policy = HttpFetchPolicy.html(Duration.ofSeconds(1));
    var publicIp = InetAddress.getByName("1.1.1.1");
    var privateIp = InetAddress.getByName("127.0.0.1");
    assertDoesNotThrow(
        () ->
            SafeHttpFetcher.validatePeer(
                new InetSocketAddress(publicIp, 443), Set.of(publicIp), policy));
    assertThrows(
        HttpFetchException.class,
        () -> SafeHttpFetcher.validatePeer(new InetSocketAddress(publicIp, 443), Set.of(), policy));
    assertThrows(
        HttpFetchException.class,
        () ->
            SafeHttpFetcher.validatePeer(
                new InetSocketAddress(privateIp, 443), Set.of(privateIp), policy));
    assertThrows(
        HttpFetchException.class,
        () ->
            SafeHttpFetcher.validatePeer(
                InetSocketAddress.createUnresolved("fixture.example", 443),
                Set.of(publicIp),
                policy));
  }

  /** 検証に使うサーバー。 */
  HttpServer server;

  /** 検証に使う実行先。 */
  ExecutorService executor;

  /** 検証に使う起点。 */
  URI origin;

  /** 各検証を独立した状態で始められるよう、依存先と検証データを準備する。 */
  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    executor = Executors.newCachedThreadPool();
    server.setExecutor(executor);
    server.start();
    origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
  }

  /** 次の検証へ状態を持ち越さないよう、利用した資源と保存先を片付ける。 */
  @AfterEach
  void stop() {
    server.stop(0);
    executor.shutdownNow();
  }

  /** 検証で使用する判断基準を、指定された条件に合わせて用意する。 */
  HttpFetchPolicy policy(int wire, int decoded, int redirects, long total, long read) {
    return new HttpFetchPolicy(
        wire,
        decoded,
        Duration.ofSeconds(1),
        Duration.ofMillis(read),
        Duration.ofMillis(total),
        redirects,
        null,
        origin,
        false);
  }

  /** 検証用に、指定された対象の情報を、呼び出し元が参照できる形で返す。 */
  SafeHttpFetcher.Response get(String path, HttpFetchPolicy policy) {
    return new SafeHttpFetcher()
        .fetch(
            origin.resolve(path),
            Map.of(),
            policy,
            FetchOperation.active(),
            HttpFetchObserver.NONE);
  }

  /** 外部情報の取得で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void chunkedBodyHasNoContentLengthAndStillHonorsWireLimit() {
    server.createContext(
        "/body",
        exchange -> {
          exchange.sendResponseHeaders(200, 0);
          try (var out = exchange.getResponseBody()) {
            out.write(new byte[65]);
          }
        });
    assertEquals(65, get("/body", policy(65, 65, 2, 2000, 1000)).body().length);
    assertEquals(
        HttpFetchException.Code.WIRE_LIMIT,
        assertThrows(HttpFetchException.class, () -> get("/body", policy(64, 100, 2, 2000, 1000)))
            .code());
  }

  /** 外部情報の取得で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void compressedWireAndExpandedBodyHaveSeparateLimits() throws Exception {
    var bytes = new ByteArrayOutputStream();
    try (var gzip = new GZIPOutputStream(bytes)) {
      gzip.write(new byte[4096]);
    }
    server.createContext(
        "/gzip",
        exchange -> {
          exchange.getResponseHeaders().set("Content-Encoding", "gzip");
          exchange.sendResponseHeaders(200, bytes.size());
          try (var out = exchange.getResponseBody()) {
            out.write(bytes.toByteArray());
          }
        });
    assertEquals(4096, get("/gzip", policy(128, 4096, 2, 2000, 1000)).body().length);
    assertEquals(
        HttpFetchException.Code.DECODED_LIMIT,
        assertThrows(HttpFetchException.class, () -> get("/gzip", policy(128, 64, 2, 2000, 1000)))
            .code());
  }

  /** 外部情報の取得で、時刻や期限に応じて、待機・実行・保存の扱いを切り替えることを確認する。 */
  @Test
  void redirectsShareDeadlineAndCannotLeaveConfiguredOrigin() {
    server.createContext(
        "/redirect",
        exchange -> {
          exchange.getResponseHeaders().set("Location", "http://169.254.169.254/latest/meta-data");
          exchange.sendResponseHeaders(302, -1);
          exchange.close();
        });
    assertEquals(
        HttpFetchException.Code.URL_NOT_ALLOWED,
        assertThrows(
                HttpFetchException.class, () -> get("/redirect", policy(128, 128, 2, 2000, 1000)))
            .code());
    server.createContext(
        "/loop",
        exchange -> {
          exchange.getResponseHeaders().set("Location", "/loop");
          exchange.sendResponseHeaders(302, -1);
          exchange.close();
        });
    var requests = new AtomicInteger();
    assertEquals(
        HttpFetchException.Code.REDIRECT_LIMIT,
        assertThrows(
                HttpFetchException.class,
                () ->
                    new SafeHttpFetcher()
                        .fetch(
                            origin.resolve("/loop"),
                            Map.of(),
                            policy(128, 128, 2, 2000, 1000),
                            FetchOperation.active(),
                            new HttpFetchObserver() {
                              /** 検証で使用する要求を、指定された条件に合わせて用意する。 */
                              public void request(boolean redirect) {
                                requests.incrementAndGet();
                              }
                            }))
            .code());
    assertEquals(3, requests.get());
  }

  /** タイムアウトを読み取ることを確認する。 */
  @Test
  void slowResponseHasReadTimeoutAndWholeOperationTimeout() {
    server.createContext(
        "/slow",
        exchange -> {
          try {
            Thread.sleep(500);
            exchange.sendResponseHeaders(200, -1);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
          } finally {
            exchange.close();
          }
        });
    assertEquals(
        HttpFetchException.Code.READ_TIMEOUT,
        assertThrows(HttpFetchException.class, () -> get("/slow", policy(128, 128, 2, 2000, 50)))
            .code());
    assertEquals(
        HttpFetchException.Code.TOTAL_TIMEOUT,
        assertThrows(HttpFetchException.class, () -> get("/slow", policy(128, 128, 2, 50, 1000)))
            .code());
  }

  /** 外部情報の取得の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void cancellationIsCheckedBeforeRequestAndPublicPageCannotUseTestOrigin() {
    var requests = new AtomicInteger();
    var observer =
        new HttpFetchObserver() {
          /** 検証で使用する要求を、指定された条件に合わせて用意する。 */
          public void request(boolean redirect) {
            requests.incrementAndGet();
          }
        };
    assertThrows(
        CancellationException.class,
        () ->
            new SafeHttpFetcher()
                .fetch(
                    origin,
                    Map.of(),
                    policy(128, 128, 2, 2000, 1000),
                    new FetchOperation(
                        () -> {
                          throw new CancellationException();
                        },
                        Long.MAX_VALUE),
                    observer));
    assertEquals(0, requests.get());
    assertThrows(
        HttpFetchException.class,
        () ->
            new SafeHttpFetcher()
                .fetch(
                    origin,
                    Map.of(),
                    HttpFetchPolicy.html(Duration.ofSeconds(1)),
                    FetchOperation.active(),
                    observer));
    assertEquals(0, requests.get());
  }

  /** 外部情報の取得の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void cancellingInFlightClosesTheConnection() throws Exception {
    var started = new CountDownLatch(1);
    var disconnected = new CountDownLatch(1);
    var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
    server.createContext(
        "/stream",
        exchange -> {
          exchange.sendResponseHeaders(200, 0);
          started.countDown();
          try (var out = exchange.getResponseBody()) {
            for (int i = 0; i < 100; i++) {
              out.write(new byte[8192]);
              out.flush();
              Thread.sleep(10);
            }
          } catch (IOException closed) {
            disconnected.countDown();
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
          } finally {
            exchange.close();
          }
        });
    try (var waiter = Executors.newSingleThreadExecutor()) {
      var future =
          waiter.submit(
              () ->
                  new SafeHttpFetcher()
                      .fetch(
                          origin.resolve("/stream"),
                          Map.of(),
                          policy(1_000_000, 1_000_000, 2, 5000, 1000),
                          new FetchOperation(
                              () -> {
                                if (cancelled.get()) throw new CancellationException();
                              },
                              Long.MAX_VALUE),
                          HttpFetchObserver.NONE));
      assertTrue(started.await(2, TimeUnit.SECONDS));
      cancelled.set(true);
      assertInstanceOf(
          CancellationException.class,
          assertThrows(ExecutionException.class, () -> future.get(2, TimeUnit.SECONDS)).getCause());
      assertTrue(disconnected.await(2, TimeUnit.SECONDS));
    }
  }

  /** 外部情報の取得で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void conflictingContentLengthAndChunkedHeadersAreRejected() throws Exception {
    try (var socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        var writer = Executors.newSingleThreadExecutor()) {
      var served =
          writer.submit(
              () -> {
                try (var peer = socket.accept()) {
                  var input =
                      new BufferedReader(
                          new InputStreamReader(
                              peer.getInputStream(), java.nio.charset.StandardCharsets.US_ASCII));
                  while (!input.readLine().isEmpty()) {}
                  peer.getOutputStream()
                      .write(
                          ("HTTP/1.1 200 OK\r\nContent-Length: 1\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n41\r\n"
                                  + "x".repeat(65)
                                  + "\r\n0\r\n\r\n")
                              .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                  peer.getOutputStream().flush();
                } catch (IOException failed) {
                  throw new UncheckedIOException(failed);
                }
              });
      URI fixture = URI.create("http://127.0.0.1:" + socket.getLocalPort());
      var policy =
          new HttpFetchPolicy(
              64,
              128,
              Duration.ofSeconds(1),
              Duration.ofSeconds(1),
              Duration.ofSeconds(2),
              2,
              null,
              fixture,
              false);
      // Netty rejects this ambiguous HTTP framing before exposing a body.
      assertEquals(
          HttpFetchException.Code.NETWORK_ERROR,
          assertThrows(
                  HttpFetchException.class,
                  () ->
                      new SafeHttpFetcher()
                          .fetch(
                              fixture,
                              Map.of(),
                              policy,
                              FetchOperation.active(),
                              HttpFetchObserver.NONE))
              .code());
      served.get(2, TimeUnit.SECONDS);
    }
  }

  /** 外部情報の取得で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void oversizedDeclaredLengthIsRejectedWithoutWaitingForTheDeclaredBody() {
    server.createContext(
        "/declared",
        exchange -> {
          exchange.sendResponseHeaders(200, 65);
          try (var out = exchange.getResponseBody()) {
            out.write(1);
            out.flush();
          } catch (IOException incompleteFixtureBody) {
            exchange.close();
          }
        });
    assertEquals(
        HttpFetchException.Code.WIRE_LIMIT,
        assertThrows(
                HttpFetchException.class, () -> get("/declared", policy(64, 128, 2, 2000, 1000)))
            .code());
  }
}
