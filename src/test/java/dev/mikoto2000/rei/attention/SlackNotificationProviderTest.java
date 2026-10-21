package dev.mikoto2000.rei.attention;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Slack通知提供元の結果と状態の契約を検証するテスト。 */
@Tag("integration")
class SlackNotificationProviderTest {
  /** 検証に使うサーバー。 */
  HttpServer server;

  /** 検証に使う提供元。 */
  SlackNotificationProvider provider;

  /** 検証に使う呼び出し回数。 */
  AtomicInteger calls = new AtomicInteger();

  /** 検証に使う本文、認証。 */
  AtomicReference<String> body = new AtomicReference<>(), auth = new AtomicReference<>();

  /** 検証に使う返信。 */
  AtomicReference<String> reply =
      new AtomicReference<>("{\"ok\":true,\"channel\":\"C123ABCDE\",\"ts\":\"123456.000001\"}");

  /** 検証に使うコード。 */
  AtomicInteger code = new AtomicInteger(200);

  /** 検証に使う識別子。 */
  String id = UUID.randomUUID().toString();

  /** 各検証を独立した状態で始められるよう、依存先と検証データを準備する。 */
  @BeforeEach
  void setup() throws Exception {
    server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/api/chat.postMessage",
        exchange -> {
          calls.incrementAndGet();
          body.set(
              new String(
                  exchange.getRequestBody().readAllBytes(),
                  java.nio.charset.StandardCharsets.UTF_8));
          auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
          byte[] bytes = reply.get().getBytes(java.nio.charset.StandardCharsets.UTF_8);
          if (code.get() == 429) exchange.getResponseHeaders().set("Retry-After", "10");
          exchange.sendResponseHeaders(code.get(), bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
    provider = new SlackNotificationProvider(properties(true, "C123ABCDE", Set.of("C123ABCDE")));
  }

  /** {@code properties} の条件を再現するための補助情報を返す。 */
  SlackNotificationProperties properties(boolean enabled, String channel, Set<String> allowed) {
    return new SlackNotificationProperties(
        enabled,
        "http://127.0.0.1:" + server.getAddress().getPort() + "/api/chat.postMessage",
        "fixture-token",
        channel,
        allowed);
  }

  /** 検証で使用する付随情報を、指定された条件に合わせて用意する。 */
  String metadata() {
    return "{\"schemaVersion\":1,\"id\":\""
        + id
        + "\",\"projectId\":\"project\",\"kind\":\"RUN_COMPLETED\",\"createdAt\":\"1970-01-01T00:00:00Z\"}";
  }

  /** 次の検証へ状態を持ち越さないよう、利用した資源と保存先を片付ける。 */
  @AfterEach
  void close() {
    provider.close();
    server.stop(0);
  }

  /** 注意の管理で、再送や重複を、受付と実行の規則に従って扱うことを確認する。 */
  @Test
  void sendsOnlyAllowlistedMetadataAndChecksProviderReceipt() throws Exception {
    var receipt = provider.deliver(id, metadata());
    assertEquals("SENT", receipt.status());
    assertEquals("C123ABCDE:123456.000001", receipt.providerReceipt());
    assertEquals("Bearer fixture-token", auth.get());
    var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body.get());
    assertEquals("C123ABCDE", json.path("channel").asText());
    assertEquals(id, json.path("client_msg_id").asText());
    assertFalse(json.path("mrkdwn").asBoolean(true));
    assertFalse(json.path("unfurl_links").asBoolean(true));
    assertTrue(json.path("text").asText().contains("RUN_COMPLETED"));
    assertFalse(body.get().contains("fixture-token"));
    assertFalse(provider.destination().contains("fixture-token"));
    assertFalse(
        properties(true, "C123ABCDE", Set.of("C123ABCDE")).toString().contains("fixture-token"));
  }

  /** 注意の管理で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void http200WithSlackErrorIsNotSuccessfulAndAmbiguousErrorsRemainUnknown() throws Exception {
    reply.set("{\"ok\":false,\"error\":\"invalid_auth\"}");
    assertEquals("FAILED", provider.deliver(id, metadata()).status());
    reply.set("{\"ok\":false,\"error\":\"internal_error\"}");
    assertEquals("UNKNOWN", provider.deliver(id, metadata()).status());
    reply.set("{\"ok\":true,\"channel\":\"COTHER123\",\"ts\":\"1.000001\"}");
    assertEquals("UNKNOWN", provider.deliver(id, metadata()).status());
    reply.set("{\"ok\":true}");
    assertEquals("UNKNOWN", provider.deliver(id, metadata()).status());
    reply.set("{\"ok\":false,\"error\":\"new_unknown_error\"}");
    assertEquals("UNKNOWN", provider.deliver(id, metadata()).status());
  }

  /** 注意の管理で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void rateLimitHasBoundedRetryAfterAndNeverResendsWithinProvider() throws Exception {
    code.set(429);
    reply.set("{\"ok\":false,\"error\":\"ratelimited\"}");
    var receipt = provider.deliver(id, metadata());
    assertEquals("FAILED", receipt.status());
    assertEquals(10, receipt.retryAfterSeconds());
    assertEquals(1, calls.get());
    reply.set("x".repeat(20000));
    var limited = provider.deliver(id, metadata());
    assertEquals("FAILED", limited.status());
    assertEquals(10, limited.retryAfterSeconds());
  }

  /** 注意の管理で、再送や重複を、受付と実行の規則に従って扱うことを確認する。 */
  @Test
  void malformedDuplicateOversizedAndServerFailuresHaveUnknownOutcome() throws Exception {
    for (String value : List.of("invalid", "{\"ok\":true,\"ok\":false}", "x".repeat(20000))) {
      reply.set(value);
      assertEquals("UNKNOWN", provider.deliver(id, metadata()).status());
    }
    code.set(500);
    assertEquals("UNKNOWN", provider.deliver(id, metadata()).status());
  }

  /** 注意の管理で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void disabledUnallowlistedWrongEndpointOrArbitraryMessageCannotSend() throws Exception {
    try (var disabled =
        new SlackNotificationProvider(properties(false, "C123ABCDE", Set.of("C123ABCDE")))) {
      assertThrows(IllegalArgumentException.class, () -> disabled.deliver(id, metadata()));
    }
    assertThrows(
        IllegalArgumentException.class, () -> properties(true, "COTHER123", Set.of("C123ABCDE")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SlackNotificationProperties(
                true,
                "https://example.com/api/chat.postMessage",
                "fixture-token",
                "C123ABCDE",
                Set.of("C123ABCDE")));
    assertThrows(
        IllegalArgumentException.class,
        () -> provider.deliver(id, "{\"message\":\"private user text\"}"));
    assertEquals(0, calls.get());
  }

  /** 注意の管理の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void interruptionBeforeAttemptCannotSendAndRestoresCallerFlag() {
    try {
      Thread.currentThread().interrupt();
      assertThrows(InterruptedException.class, () -> provider.deliver(id, metadata()));
      assertTrue(Thread.currentThread().isInterrupted());
      assertEquals(0, calls.get());
    } finally {
      Thread.interrupted();
    }
  }

  /** 注意の管理で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void conditionalProviderBindsAdministratorConfigurationAndDefaultIsAbsent() {
    var runner =
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
            .withUserConfiguration(SlackNotificationProvider.class);
    runner.run(context -> assertFalse(context.containsBean("slackNotificationProvider")));
    runner
        .withPropertyValues(
            "rei.attention.slack.enabled=true",
            "rei.attention.slack.endpoint="
                + properties(true, "C123ABCDE", Set.of("C123ABCDE")).endpoint(),
            "rei.attention.slack.bot-token=fixture-token",
            "rei.attention.slack.channel=C123ABCDE",
            "rei.attention.slack.channels=C123ABCDE")
        .run(
            context -> {
              assertNull(context.getStartupFailure());
              assertEquals("SLACK", context.getBean(SlackNotificationProvider.class).name());
            });
  }

  /** 注意の管理で、時刻や期限に応じて、待機・実行・保存の扱いを切り替えることを確認する。 */
  @Test
  void redirectsAreNotFollowedAndActualTransportTimeoutIsUnknown() throws Exception {
    server.createContext(
        "/redirect-target",
        exchange -> {
          calls.incrementAndGet();
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    server.removeContext("/api/chat.postMessage");
    server.createContext(
        "/api/chat.postMessage",
        exchange -> {
          calls.incrementAndGet();
          exchange.getRequestBody().readAllBytes();
          exchange
              .getResponseHeaders()
              .set(
                  "Location",
                  "http://127.0.0.1:" + server.getAddress().getPort() + "/redirect-target");
          exchange.sendResponseHeaders(302, -1);
          exchange.close();
        });
    assertEquals("FAILED", provider.deliver(id, metadata()).status());
    assertEquals(1, calls.get());
    server.removeContext("/api/chat.postMessage");
    var gate = new java.util.concurrent.CountDownLatch(1);
    server.createContext(
        "/api/chat.postMessage",
        exchange -> {
          calls.incrementAndGet();
          try {
            exchange.getRequestBody().readAllBytes();
            gate.await(4, java.util.concurrent.TimeUnit.SECONDS);
            exchange.sendResponseHeaders(200, -1);
          } catch (Exception ignored) {
          } finally {
            exchange.close();
          }
        });
    long start = System.nanoTime();
    try {
      assertEquals("UNKNOWN", provider.deliver(id, metadata()).status());
      assertTrue(System.nanoTime() - start < java.time.Duration.ofSeconds(4).toNanos());
      assertEquals(2, calls.get());
    } finally {
      gate.countDown();
    }
  }
}
