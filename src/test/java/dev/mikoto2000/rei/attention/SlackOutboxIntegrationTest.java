package dev.mikoto2000.rei.attention;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.policy.ActionCapability;
import dev.mikoto2000.rei.core.policy.ToolPermissionPolicy;
import dev.mikoto2000.rei.core.policy.ToolPermissionProperties;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.InMemoryAgentEventBus;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** SlackOutbox外部連携の結果と状態の契約を検証するテスト。 */
@Tag("integration")
class SlackOutboxIntegrationTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** 日時や期限の条件を再現するために使う検証時刻。 */
  Clock clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);

  /** 検証に使うデータ。 */
  DriverManagerDataSource data;

  /** 検証に使うinbox。 */
  AttentionRepository inbox;

  /** 検証に使うoutbox。 */
  AttentionDeliveryRepository outbox;

  /** 検証に使うサービス。 */
  AttentionDeliveryService service;

  /** 検証に使うSlack。 */
  SlackNotificationProvider slack;

  /** 検証に使うWebhook通知。 */
  JdkAttentionSender webhook;

  /** 検証に使うサーバー。 */
  HttpServer server;

  /** 検証に使う呼び出し回数、コード。 */
  AtomicInteger calls = new AtomicInteger(), code = new AtomicInteger(200);

  /** 検証に使う通知の連携先。 */
  InMemoryAgentEventBus bus = new InMemoryAgentEventBus();

  /** 各検証を独立した状態で始められるよう、依存先と検証データを準備する。 */
  @BeforeEach
  void setup() throws Exception {
    data = new DriverManagerDataSource("jdbc:sqlite:" + root.resolve("outbox.db"));
    inbox = new AttentionRepository(data, clock);
    outbox = new AttentionDeliveryRepository(data, clock);
    server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/api/chat.postMessage",
        exchange -> {
          calls.incrementAndGet();
          exchange.getRequestBody().readAllBytes();
          String response =
              code.get() == 200
                  ? "{\"ok\":true,\"channel\":\"C123ABCDE\",\"ts\":\"1.000001\"}"
                  : "{\"ok\":false,\"error\":\"ratelimited\"}";
          if (code.get() == 429) exchange.getResponseHeaders().set("Retry-After", "10");
          byte[] body = response.getBytes(java.nio.charset.StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(code.get(), body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    slack =
        new SlackNotificationProvider(
            new SlackNotificationProperties(
                true,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/api/chat.postMessage",
                "fixture-secret",
                "C123ABCDE",
                Set.of("C123ABCDE")));
    webhook = new JdkAttentionSender();
    start(clock, true);
  }

  /** 検証用に、要求された処理を開始する。 */
  void start(Clock current, boolean granted) {
    outbox = new AttentionDeliveryRepository(data, current);
    var policy =
        new ToolPermissionPolicy(
            new ToolPermissionProperties(
                true,
                granted
                    ? Set.of(ActionCapability.NETWORK_WRITE, ActionCapability.EXTERNAL_SIDE_EFFECT)
                    : Set.of(),
                null,
                null));
    service =
        new AttentionDeliveryService(
            new AttentionDeliveryProperties(true, false, "", "", Set.of("project"), "SLACK"),
            inbox,
            outbox,
            bus,
            policy,
            webhook);
    service.setSlack(slack);
    service.start();
  }

  /** {@code fact} の条件を再現するための補助情報を返す。 */
  AttentionRepository.Item fact(String run) {
    var event =
        new AgentEventFactory(clock)
            .runCompleted(run, 1)
            .withOwnership(new AgentRunContext(run, "session", root, "project"));
    return inbox
        .create(event, "RUN_COMPLETED", run, "private message must stay in Inbox")
        .orElseThrow();
  }

  /** 次の検証へ状態を持ち越さないよう、利用した資源と保存先を片付ける。 */
  @AfterEach
  void close() {
    service.close();
    slack.close();
    webhook.close();
    server.stop(0);
  }

  /** 注意の管理で、再送や重複を、受付と実行の規則に従って扱うことを確認する。 */
  @Test
  void sharedOutboxDeduplicatesAndPersistsSlackReceiptAndRateAcrossRestart() {
    var first = fact("first");
    service.request("project", first.id());
    service.request("project", first.id());
    service.dispatchOne();
    assertEquals(1, calls.get());
    assertEquals("SLACK", service.status("project", first.id()).provider());
    assertEquals("C123ABCDE:1.000001", service.status("project", first.id()).providerReceipt());
    var second = fact("second");
    service.request("project", second.id());
    service.dispatchOne();
    assertEquals(1, calls.get());
    assertEquals(0, service.status("project", second.id()).attempts());
    service.close();
    start(Clock.offset(clock, Duration.ofMillis(1001)), true);
    service.dispatchOne();
    assertEquals(2, calls.get());
    assertEquals("SENT", service.status("project", second.id()).status());
    assertEquals("C123ABCDE:1.000001", service.status("project", first.id()).providerReceipt());
    String persisted =
        org.springframework.jdbc.core.simple.JdbcClient.create(data)
            .sql(
                "SELECT group_concat(destination||reason||provider_receipt) FROM attention_delivery")
            .query(String.class)
            .single();
    assertFalse(persisted.contains("fixture-secret"));
    assertFalse(persisted.contains("private message"));
  }

  /** 注意の管理で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void rateLimitBlocksAcknowledgedManualRetryUntilPersistedDeadline() {
    code.set(429);
    var item = fact("rate");
    service.request("project", item.id());
    service.dispatchOne();
    assertEquals("FAILED", service.status("project", item.id()).status());
    assertThrows(IllegalArgumentException.class, () -> service.retry("project", item.id(), false));
    service.retry("project", item.id(), true);
    service.dispatchOne();
    assertEquals(1, calls.get());
    service.close();
    start(Clock.offset(clock, Duration.ofSeconds(9)), true);
    service.dispatchOne();
    assertEquals(1, calls.get());
    service.close();
    start(Clock.offset(clock, Duration.ofSeconds(10)), true);
    code.set(200);
    service.dispatchOne();
    assertEquals(2, calls.get());
    assertEquals("SENT", service.status("project", item.id()).status());
  }

  /** 注意の管理で、再送や重複を、受付と実行の規則に従って扱うことを確認する。 */
  @Test
  void policyOrProviderDisableCannotSendAndUncertainClaimNeverReplays() {
    var item = fact("uncertain");
    service.request("project", item.id());
    assertTrue(outbox.claim("project", item.id()));
    service.close();
    start(Clock.offset(clock, Duration.ofSeconds(2)), true);
    assertEquals("UNKNOWN", service.status("project", item.id()).status());
    service.dispatchOne();
    assertEquals(0, calls.get());
    var denied = fact("denied");
    service.close();
    start(clock, false);
    service.request("project", denied.id());
    service.dispatchOne();
    assertEquals("BLOCKED", service.status("project", denied.id()).status());
    assertEquals(0, calls.get());
    service.setSlack(null);
    assertThrows(
        IllegalArgumentException.class, () -> service.request("project", fact("disabled").id()));
  }

  /** 明示的な再試行を必要とすることを確認する。 */
  @Test
  void administratorProviderChangeRequiresExplicitRetryAndUpdatesPersistedProvider() {
    var item = fact("provider-change");
    outbox.enqueue(item, "legacy-destination", "MANUAL");
    service.dispatchOne();
    assertEquals("BLOCKED", service.status("project", item.id()).status());
    assertEquals(0, calls.get());
    service.retry("project", item.id(), false);
    service.dispatchOne();
    assertEquals("SENT", service.status("project", item.id()).status());
    assertEquals("SLACK", service.status("project", item.id()).provider());
    assertEquals(1, calls.get());
  }

  /** 注意の管理で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void oldDatabaseRowsMigrateWithoutLosingPendingWebhookDelivery() {
    var legacy = new DriverManagerDataSource("jdbc:sqlite:" + root.resolve("legacy.db"));
    var db = org.springframework.jdbc.core.simple.JdbcClient.create(legacy);
    db.sql(
            "CREATE TABLE attention_delivery(id TEXT PRIMARY KEY,project TEXT NOT NULL,destination TEXT NOT NULL,cause TEXT NOT NULL,status TEXT NOT NULL,attempts INTEGER NOT NULL,reason TEXT NOT NULL,updated INTEGER NOT NULL)")
        .update();
    String id = UUID.randomUUID().toString();
    db.sql(
            "INSERT INTO attention_delivery VALUES(?,?,'destination','MANUAL','PENDING',0,'queued',0)")
        .params(id, "project")
        .update();
    var migrated = new AttentionDeliveryRepository(legacy, clock);
    assertEquals("WEBHOOK", migrated.find("project", id).orElseThrow().provider());
    assertTrue(migrated.claim("project", id));
    migrated.finish(migrated.find("project", id).orElseThrow(), "SENT", "http_204");
    assertEquals(
        "SENT",
        new AttentionDeliveryRepository(legacy, clock).find("project", id).orElseThrow().status());
  }

  /** 注意の管理で、秘密情報の保護と利用範囲が契約に従うことを確認する。 */
  @Test
  void credentialRotationBlocksOldDestinationWithoutLeakingEitherSecret() {
    var item = fact("rotation");
    service.request("project", item.id());
    slack.close();
    slack =
        new SlackNotificationProvider(
            new SlackNotificationProperties(
                true,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/api/chat.postMessage",
                "rotated-secret",
                "C123ABCDE",
                Set.of("C123ABCDE")));
    service.setSlack(slack);
    service.dispatchOne();
    assertEquals("BLOCKED", service.status("project", item.id()).status());
    assertEquals(0, calls.get());
    service.retry("project", item.id(), false);
    service.dispatchOne();
    assertEquals("SENT", service.status("project", item.id()).status());
    assertFalse(service.status("project", item.id()).toString().contains("rotated-secret"));
  }
}
