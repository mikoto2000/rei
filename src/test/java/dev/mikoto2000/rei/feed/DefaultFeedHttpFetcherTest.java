package dev.mikoto2000.rei.feed;

import static dev.mikoto2000.rei.feed.FeedAuthenticationTest.properties;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** 既定フィードHTTP通信情報の取得元の結果と状態の契約を検証するテスト。 */
@Tag("integration")
class DefaultFeedHttpFetcherTest {
  /** 検証に使うサーバー。 */
  private HttpServer server;

  /** 検証に使うその他。 */
  private HttpServer other;

  /** 検証に使う起点。 */
  private String origin;

  /** 検証に使うトークン。 */
  private final String token = "a".repeat(43);

  /** 各検証を独立した状態で始められるよう、依存先と検証データを準備する。 */
  @BeforeEach
  void setUp() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    other = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    origin = "http://127.0.0.1:" + server.getAddress().getPort();
    server.start();
    other.start();
  }

  /** 次の検証へ状態を持ち越さないよう、利用した資源と保存先を片付ける。 */
  @AfterEach
  void tearDown() {
    server.stop(0);
    other.stop(0);
  }

  /** 認証ヘッダーを設定済みの購読先だけへ送り、ほかの公開フィードへ転送しないことを確認する。 */
  @Test
  void sendsBearerOnlyForConfiguredFeedAndNeverForOtherPublicFeed() {
    AtomicReference<String> header = new AtomicReference<>();
    server.createContext(
        "/feed.xml",
        exchange -> {
          header.set(exchange.getRequestHeaders().getFirst("Authorization"));
          exchange.sendResponseHeaders(200, 2);
          exchange.getResponseBody().write("ok".getBytes());
          exchange.close();
        });
    server.createContext(
        "/public.xml",
        exchange -> {
          header.set(exchange.getRequestHeaders().getFirst("Authorization"));
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    var fetcher = authenticated();
    assertEquals(200, fetcher.fetch(URI.create(origin + "/feed.xml")).statusCode());
    assertEquals("Bearer " + token, header.get());
    fetcher.fetch(URI.create(origin + "/public.xml"));
    assertNull(header.get());
  }

  /** フィードの購読と収集で、秘密情報の保護と利用範囲が契約に従うことを確認する。 */
  @Test
  void missingSecretMakesNoRequest() {
    AtomicInteger calls = new AtomicInteger();
    server.createContext(
        "/",
        exchange -> {
          calls.incrementAndGet();
          exchange.sendResponseHeaders(401, -1);
          exchange.close();
        });
    var fetcher =
        new DefaultFeedHttpFetcher(
            new FeedAuthentication(
                properties(origin + "/feed.xml", origin, "REI_FEED_TOKEN"), name -> null, true));
    assertThrows(FeedFetchException.class, () -> fetcher.fetch(URI.create(origin + "/feed.xml")));
    assertEquals(0, calls.get());
  }

  /** フィードの購読と収集で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void authenticatedRedirectsAreNeverFollowedEvenToSameOrigin() {
    AtomicInteger forwarded = new AtomicInteger();
    other.createContext(
        "/target",
        exchange -> {
          forwarded.incrementAndGet();
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    server.createContext(
        "/target",
        exchange -> {
          forwarded.incrementAndGet();
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    AtomicReference<String> location =
        new AtomicReference<>("http://127.0.0.1:" + other.getAddress().getPort() + "/target");
    server.createContext(
        "/feed.xml",
        exchange -> {
          exchange.getResponseHeaders().add("Location", location.get());
          exchange.sendResponseHeaders(302, -1);
          exchange.close();
        });
    for (String target : new String[] {location.get(), origin + "/target"}) {
      location.set(target);
      var error =
          assertThrows(
              FeedFetchException.class,
              () -> authenticated().fetch(URI.create(origin + "/feed.xml")));
      assertEquals(302, error.httpStatus());
      assertFalse(error.toString().contains(token));
    }
    assertEquals(0, forwarded.get());
  }

  /** フィードの購読と収集で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void publicRedirectBehaviorIsPreservedWithoutAuthorization() {
    AtomicReference<String> header = new AtomicReference<>();
    server.createContext(
        "/public.xml",
        exchange -> {
          exchange.getResponseHeaders().add("Location", "/target");
          exchange.sendResponseHeaders(302, -1);
          exchange.close();
        });
    server.createContext(
        "/target",
        exchange -> {
          header.set(exchange.getRequestHeaders().getFirst("Authorization"));
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    assertEquals(200, authenticated().fetch(URI.create(origin + "/public.xml")).statusCode());
    assertNull(header.get());
  }

  /** フィードの購読と収集の操作が、指定された権限・承認条件に従うことを確認する。 */
  @Test
  void unauthorizedResponsesAreActionableButNeverEchoBody() {
    AtomicInteger status = new AtomicInteger(401);
    server.createContext(
        "/feed.xml",
        exchange -> {
          byte[] body = ("echo " + token).getBytes();
          exchange.sendResponseHeaders(status.get(), body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    for (int code : new int[] {401, 403}) {
      status.set(code);
      var error =
          assertThrows(
              FeedFetchException.class,
              () -> authenticated().fetch(URI.create(origin + "/feed.xml")));
      assertEquals(code, error.httpStatus());
      assertTrue(error.getMessage().contains(Integer.toString(code)));
      assertFalse(error.toString().contains(token));
      assertNull(error.getCause());
    }
  }

  /** フィードの購読と収集で、保存した情報を、再起動や復元後の処理へ正しく引き継ぐことを確認する。 */
  @Test
  void reflectedTokenInSuccessfulResponseIsNotReturnedForPersistence() {
    server.createContext(
        "/feed.xml",
        exchange -> {
          byte[] body = ("<title>" + token + "</title>").getBytes();
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    var response = authenticated().fetch(URI.create(origin + "/feed.xml"));
    assertEquals(200, response.statusCode());
    assertFalse(response.body().contains(token));
  }

  /** {@code authenticated} の条件を再現するための補助情報を返す。 */
  private DefaultFeedHttpFetcher authenticated() {
    return new DefaultFeedHttpFetcher(
        new FeedAuthentication(
            properties(origin + "/feed.xml", origin, "REI_FEED_TOKEN"), name -> token, true));
  }
}
