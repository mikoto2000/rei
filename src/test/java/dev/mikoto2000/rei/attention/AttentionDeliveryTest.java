package dev.mikoto2000.rei.attention;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.policy.ActionCapability;
import dev.mikoto2000.rei.core.policy.ToolPermissionPolicy;
import dev.mikoto2000.rei.core.policy.ToolPermissionProperties;
import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventType;
import dev.mikoto2000.rei.event.AttentionRequiredPayload;
import dev.mikoto2000.rei.event.GitHubLifecyclePayload;
import dev.mikoto2000.rei.event.InMemoryAgentEventBus;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 注意配信の結果と状態の契約を検証するテスト。 */
@Tag("integration")
class AttentionDeliveryTest {
  @Test
  void lifeDigestUsesTheExistingAutomaticOutbox() {
    start(properties(true, true), true);
    var life = org.mockito.Mockito.mock(dev.mikoto2000.rei.life.LifeContext.class);
    attention.setLifeContext(life);
    fact("held-for-work");
    org.mockito.Mockito.when(
            life.notificationAllowed(
                org.mockito.Mockito.anyString(), org.mockito.Mockito.anyString()))
        .thenReturn(true);
    attention.releaseHeld();
    var digest =
        inbox.list("project").stream()
            .filter(i -> i.kind().equals("LIFE_DIGEST"))
            .findFirst()
            .orElseThrow();
    assertTrue(outbox.find("project", digest.id()).isPresent());
    delivery.dispatchOne();
    assertEquals(1, calls.get());
  }

  @Test
  void secretaryDeliveryUsesTheExistingAutomaticOutbox() {
    start(properties(true, true), true);
    var notice =
        new dev.mikoto2000.rei.secretary.SecretaryNotifications.Notice(
            UUID.randomUUID().toString(),
            "project",
            null,
            "BRIEFING",
            "summary",
            3,
            "DELIVERY",
            Instant.EPOCH);
    attention.deliver(notice);
    var item = inbox.list("project").getFirst();
    assertTrue(outbox.find("project", item.id()).isPresent());
    delivery.dispatchOne();
    assertEquals(1, calls.get());
  }

  @Test
  void lifeHoldDoesNotSendOrConsumeTransportAttemptsAndResumesOnce() {
    start(properties(true, false), true);
    var item = fact("life");
    delivery.request("project", item.id());
    var life = org.mockito.Mockito.mock(dev.mikoto2000.rei.life.LifeContext.class);
    delivery.setLifeContext(life);
    delivery.dispatchOne();
    assertEquals(0, calls.get());
    assertEquals(0, outbox.find("project", item.id()).orElseThrow().attempts());
    assertEquals("PENDING", outbox.find("project", item.id()).orElseThrow().status());
    org.mockito.Mockito.when(life.notificationAllowed("NORMAL", item.id())).thenReturn(true);
    delivery.dispatchOne();
    delivery.dispatchOne();
    assertEquals(1, calls.get());
  }

  /** 起動時について、活動中利用権を保持することを確認する。 */
  @Test
  void startupPreservesActiveLeaseAndLostLeaseBecomesUnknown() {
    start(properties(true, false), true);
    var item = fact("lease");
    var pending = delivery.request("project", item.id());
    var lease = outbox.claimActive(pending, 0);
    assertNotNull(lease);
    var restarted =
        new AttentionDeliveryRepository(
            new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("inbox.db")), clock);
    restarted.recover();
    assertEquals("SENDING", restarted.find("project", item.id()).orElseThrow().status());
    lease.close();
    restarted.recover();
    assertEquals("UNKNOWN", restarted.find("project", item.id()).orElseThrow().status());
    assertEquals(0, calls.get());
  }

  /** 注意の管理で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void oldAttemptCannotOverwriteExplicitlyRetriedClaim() {
    start(properties(true, false), true);
    var item = fact("late-attempt");
    var pending = delivery.request("project", item.id());
    assertTrue(outbox.claim("project", item.id()));
    var old = outbox.find("project", item.id()).orElseThrow();
    outbox.recover();
    outbox.retry("project", item.id(), pending.destination(), true);
    assertTrue(outbox.claim("project", item.id()));
    var current = outbox.find("project", item.id()).orElseThrow();
    outbox.finish(old, "SENT", "late_outcome");
    assertEquals("SENDING", outbox.find("project", item.id()).orElseThrow().status());
    outbox.finish(current, "SENT", "current_outcome");
    assertEquals("SENT", outbox.find("project", item.id()).orElseThrow().status());
  }

  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path dir;

  /** 日時や期限の条件を再現するために使う検証時刻。 */
  Clock clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);

  /** 検証に使うinbox。 */
  AttentionRepository inbox;

  /** 検証に使うoutbox。 */
  AttentionDeliveryRepository outbox;

  /** 検証に使う通知の連携先。 */
  InMemoryAgentEventBus bus;

  /** 検証に使う注意。 */
  AttentionService attention;

  /** 検証に使う配信。 */
  AttentionDeliveryService delivery;

  /** 検証に使うsender。 */
  JdkAttentionSender sender;

  /** 検証に使うサーバー。 */
  HttpServer server;

  /** 検証に使う呼び出し回数。 */
  AtomicInteger calls = new AtomicInteger();

  /** 検証に使う応答。 */
  AtomicInteger response = new AtomicInteger(204);

  /** 検証に使う本文、権限の確認、照合キー。 */
  AtomicReference<String> body = new AtomicReference<>(),
      authorization = new AtomicReference<>(),
      key = new AtomicReference<>();

  /** 各検証を独立した状態で始められるよう、依存先と検証データを準備する。 */
  @BeforeEach
  void setup() throws Exception {
    var source = new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("inbox.db"));
    inbox = new AttentionRepository(source, clock);
    outbox = new AttentionDeliveryRepository(source, clock);
    bus = new InMemoryAgentEventBus();
    attention = new AttentionService(inbox, bus, bus, clock, () -> 0);
    attention.start();
    server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/notify",
        exchange -> {
          calls.incrementAndGet();
          body.set(
              new String(
                  exchange.getRequestBody().readAllBytes(),
                  java.nio.charset.StandardCharsets.UTF_8));
          authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
          key.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
          exchange.sendResponseHeaders(response.get(), -1);
          exchange.close();
        });
    server.start();
    sender = new JdkAttentionSender();
  }

  /** {@code properties} の条件を再現するための補助情報を返す。 */
  AttentionDeliveryProperties properties(boolean enabled, boolean automatic) {
    return new AttentionDeliveryProperties(
        enabled,
        automatic,
        "http://127.0.0.1:" + server.getAddress().getPort() + "/notify",
        "fixture-token",
        Set.of("project"));
  }

  /** 検証で使用する判断基準を、指定された条件に合わせて用意する。 */
  ToolPermissionPolicy policy(boolean grant) {
    return new ToolPermissionPolicy(
        new ToolPermissionProperties(
            true,
            grant
                ? Set.of(ActionCapability.NETWORK_WRITE, ActionCapability.EXTERNAL_SIDE_EFFECT)
                : null,
            null,
            null));
  }

  /** 検証用に、要求された処理を開始する。 */
  void start(AttentionDeliveryProperties props, boolean grant) {
    delivery = new AttentionDeliveryService(props, inbox, outbox, bus, policy(grant), sender);
    delivery.start();
  }

  /** {@code fact} の条件を再現するための補助情報を返す。 */
  AttentionRepository.Item fact(String run) {
    bus.publish(
        new AgentEventFactory(clock)
            .runCompleted(run, 1)
            .withOwnership(new AgentRunContext(run, "session", dir, "project")));
    String id =
        org.springframework.jdbc.core.simple.JdbcClient.create(
                new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("inbox.db")))
            .sql("SELECT id FROM agent_attention WHERE project=? AND run=?")
            .params("project", run)
            .query(String.class)
            .single();
    return inbox.get("project", id);
  }

  /** 次の検証へ状態を持ち越さないよう、利用した資源と保存先を片付ける。 */
  @AfterEach
  void close() {
    if (delivery != null) delivery.close();
    attention.close();
    sender.close();
    server.stop(0);
  }

  /** 注意の管理で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void newOwnedFactQueuesThenDeliversOnlyMetadataOnce() {
    start(properties(true, true), true);
    var item = fact("run");
    assertEquals(0, calls.get());
    assertEquals("PENDING", delivery.status("project", item.id()).status());
    delivery.dispatchOne();
    delivery.dispatchOne();
    assertEquals(1, calls.get());
    assertEquals("SENT", delivery.status("project", item.id()).status());
    assertEquals("Bearer fixture-token", authorization.get());
    assertEquals(item.id(), key.get());
    assertTrue(body.get().contains("RUN_COMPLETED"));
    assertFalse(body.get().contains("session"));
    assertFalse(body.get().contains("message"));
    assertFalse(body.get().contains("fixture-token"));
    assertEquals("OPEN", inbox.get("project", item.id()).status());
    assertThrows(IllegalArgumentException.class, () -> delivery.request("other", item.id()));
    assertThrows(IllegalArgumentException.class, () -> delivery.retry("project", item.id(), true));
  }

  /** 注意の管理で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void githubNoticeUsesTheExistingGatedDeliveryQueueWithoutExportingPayload() {
    start(properties(true, true), true);
    var fact =
        new dev.mikoto2000.rei.github.GitHubFact(
            "REVIEW_SUBMITTED",
            "pull_request_review",
            "submitted",
            "owner/repo",
            "main",
            17,
            "b".repeat(40),
            "approved",
            null,
            Instant.EPOCH);
    var source =
        new AgentEvent(
            "github-fact",
            0,
            Instant.EPOCH,
            AgentEventType.GITHUB_REVIEW_SUBMITTED,
            1,
            "session",
            null,
            null,
            fact.sourceId(),
            null,
            new GitHubLifecyclePayload(UUID.randomUUID().toString(), fact.sourceId(), fact),
            "project");
    var item = inbox.createGitHub(source, "GITHUB_REVIEW_SUBMITTED", "review notice").orElseThrow();
    var notice =
        new AgentEvent(
            "notice",
            0,
            Instant.EPOCH,
            AgentEventType.ATTENTION_REQUIRED,
            1,
            "session",
            null,
            null,
            item.id(),
            source.id(),
            new AttentionRequiredPayload(item.id(), item.kind(), item.message()),
            "project");
    bus.publishBoundary(notice);
    bus.publishBoundary(notice);
    assertEquals("PENDING", delivery.status("project", item.id()).status());
    delivery.dispatchOne();
    assertEquals(1, calls.get());
    assertTrue(body.get().contains("GITHUB_REVIEW_SUBMITTED"));
    assertFalse(body.get().contains("owner/repo"));
    assertFalse(body.get().contains("review notice"));
  }

  /** 注意の管理で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void disabledAutomaticAndPolicyGatesNeverSendAndDoNotBackfill() {
    var old = fact("old");
    start(properties(true, false), false);
    delivery.dispatchOne();
    assertEquals("NOT_REQUESTED", delivery.status("project", old.id()).status());
    var item = fact("new");
    delivery.request("project", item.id());
    delivery.dispatchOne();
    assertEquals("BLOCKED", delivery.status("project", item.id()).status());
    assertEquals(0, calls.get());
    delivery.close();
    start(properties(false, true), true);
    assertThrows(IllegalArgumentException.class, () -> delivery.request("project", old.id()));
    delivery.dispatchOne();
    assertEquals(0, calls.get());
  }

  /** 注意の管理で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void httpFailureNeverRetriesWithoutExplicitRiskAcknowledgement() {
    response.set(500);
    start(properties(true, true), true);
    var item = fact("run");
    delivery.dispatchOne();
    delivery.dispatchOne();
    assertEquals(1, calls.get());
    assertEquals("FAILED", delivery.status("project", item.id()).status());
    assertThrows(IllegalArgumentException.class, () -> delivery.retry("project", item.id(), false));
    delivery.retry("project", item.id(), true);
    delivery.dispatchOne();
    delivery.retry("project", item.id(), true);
    delivery.dispatchOne();
    assertEquals(3, calls.get());
    assertThrows(IllegalArgumentException.class, () -> delivery.retry("project", item.id(), true));
    assertEquals("OPEN", inbox.get("project", item.id()).status());
  }

  /** 再開について、待機中を保持することを確認する。 */
  @Test
  void restartRetainsPendingButNeverReplaysAnUncertainAttempt() {
    start(properties(true, true), true);
    var pending = fact("pending");
    var uncertain = fact("uncertain");
    outbox.claim("project", uncertain.id());
    delivery.close();
    var source = new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("inbox.db"));
    outbox = new AttentionDeliveryRepository(source, clock);
    start(properties(true, true), true);
    assertEquals("UNKNOWN", delivery.status("project", uncertain.id()).status());
    delivery.dispatchOne();
    delivery.dispatchOne();
    assertEquals(1, calls.get());
    assertEquals("SENT", delivery.status("project", pending.id()).status());
  }

  /** 注意の管理で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void acknowledgementAndDestinationChangeSuppressUnsentNotifications() {
    start(properties(true, true), true);
    var ack = fact("ack");
    inbox.acknowledge("project", ack.id());
    delivery.dispatchOne();
    assertEquals("SUPPRESSED", delivery.status("project", ack.id()).status());
    var changed = fact("changed");
    delivery.close();
    start(
        new AttentionDeliveryProperties(
            true,
            true,
            properties(true, true).endpoint() + "-changed",
            "fixture-token",
            Set.of("project")),
        true);
    delivery.dispatchOne();
    assertEquals("BLOCKED", delivery.status("project", changed.id()).status());
    assertEquals(0, calls.get());
  }

  /** 注意の管理で、秘密情報の保護と利用範囲が契約に従うことを確認する。 */
  @Test
  void configurationRejectsAmbiguousDestinationsAndHidesCredentials() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AttentionDeliveryProperties(
                true, true, "http://example.com/", null, Set.of("project")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AttentionDeliveryProperties(
                true, true, "https://user:secret@example.com/", null, Set.of("project")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AttentionDeliveryProperties(
                true, true, "https://example.com/?token=secret", null, Set.of("project")));
    assertThrows(
        IllegalArgumentException.class,
        () -> new AttentionDeliveryProperties(true, true, "https://example.com/", null, Set.of()));
    assertFalse(properties(true, true).toString().contains("fixture-token"));
    assertEquals(
        Set.of(ActionCapability.NETWORK_WRITE, ActionCapability.EXTERNAL_SIDE_EFFECT),
        policy(true).capabilities("deliverAttention"));
  }

  /** 注意の管理で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void forgedEventsForeignProjectsAndDeniedPolicyCannotExportInboxFacts() {
    start(properties(true, true), true);
    var event =
        new AgentEvent(
            "forged",
            0,
            Instant.EPOCH,
            AgentEventType.ATTENTION_REQUIRED,
            1,
            "session",
            null,
            "run",
            "absent",
            null,
            new AttentionRequiredPayload("absent", "RUN_COMPLETED", "secret"),
            "project");
    bus.publish(event);
    assertTrue(outbox.pending().isEmpty());
    var foreign =
        new AgentEventFactory(clock)
            .runCompleted("foreign", 1)
            .withOwnership(new AgentRunContext("foreign", "session", dir, "other"));
    bus.publish(foreign);
    assertTrue(outbox.pending().isEmpty());
    var item = fact("owned");
    delivery.close();
    delivery =
        new AttentionDeliveryService(
            properties(true, true),
            inbox,
            outbox,
            bus,
            new ToolPermissionPolicy(
                new ToolPermissionProperties(
                    true,
                    Set.of(ActionCapability.NETWORK_WRITE, ActionCapability.EXTERNAL_SIDE_EFFECT),
                    Set.of(ActionCapability.EXTERNAL_SIDE_EFFECT),
                    null)),
            sender);
    delivery.start();
    delivery.dispatchOne();
    assertEquals("BLOCKED", delivery.status("project", item.id()).status());
    assertEquals(0, calls.get());
  }

  /** 注意の管理で、再送や重複を、受付と実行の規則に従って扱うことを確認する。 */
  @Test
  void queueCapacityAndConcurrentClaimsAreAtomicAndRequestsAreIdempotent() throws Exception {
    start(properties(true, false), true);
    for (int i = 0; i < 255; i++) {
      var item = fact("fill" + i);
      delivery.request("project", item.id());
    }
    var first = fact("last-a");
    var second = fact("last-b");
    var gate = new java.util.concurrent.CountDownLatch(1);
    var accepted = new AtomicInteger();
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var tasks =
          List.of(first, second).stream()
              .map(
                  item ->
                      pool.submit(
                          () -> {
                            gate.await();
                            try {
                              delivery.request("project", item.id());
                              accepted.incrementAndGet();
                            } catch (IllegalArgumentException full) {
                            }
                            return null;
                          }))
              .toList();
      gate.countDown();
      for (var task : tasks) task.get();
    }
    assertEquals(1, accepted.get());
    assertEquals(256, outbox.pending().size());
    var queued = outbox.pending().getFirst();
    delivery.request("project", queued.id());
    assertEquals(256, outbox.pending().size());
    var next = outbox.pending().get(1);
    var wins = new AtomicInteger();
    var claimGate = new java.util.concurrent.CountDownLatch(1);
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var tasks =
          List.of(queued, next).stream()
              .map(
                  item ->
                      pool.submit(
                          () -> {
                            claimGate.await();
                            if (outbox.claim(item.projectId(), item.id())) wins.incrementAndGet();
                            return null;
                          }))
              .toList();
      claimGate.countDown();
      for (var task : tasks) task.get();
    }
    assertEquals(1, wins.get());
  }

  /** 注意の管理の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void interruptionRecordsUnknownAndRestoresInterruptWithoutAutomaticRetry() {
    var failing = org.mockito.Mockito.mock(JdkAttentionSender.class);
    try {
      org.mockito.Mockito.when(
              failing.send(
                  org.mockito.ArgumentMatchers.any(),
                  org.mockito.ArgumentMatchers.anyString(),
                  org.mockito.ArgumentMatchers.anyString()))
          .thenThrow(new InterruptedException());
    } catch (Exception impossible) {
      throw new AssertionError(impossible);
    }
    delivery =
        new AttentionDeliveryService(
            properties(true, true), inbox, outbox, bus, policy(true), failing);
    delivery.start();
    var item = fact("interrupt");
    try {
      delivery.dispatchOne();
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
    assertEquals("UNKNOWN", delivery.status("project", item.id()).status());
    delivery.dispatchOne();
    assertEquals(0, calls.get());
    assertThrows(IllegalArgumentException.class, () -> delivery.retry("project", item.id(), false));
  }

  /** リスク確認記録を必要とすることを確認する。 */
  @Test
  void shellControlsQueueExplicitlyAndRequireRiskAcknowledgement() {
    start(properties(true, false), true);
    var item = fact("cli");
    var projects = org.mockito.Mockito.mock(dev.mikoto2000.rei.core.project.ProjectService.class);
    var project = org.mockito.Mockito.mock(dev.mikoto2000.rei.core.project.ProjectContext.class);
    org.mockito.Mockito.when(project.id()).thenReturn("project");
    org.mockito.Mockito.when(projects.currentContext()).thenReturn(project);
    var command = new AttentionCommand(inbox, projects);
    command.setDelivery(delivery);
    var text = new java.io.StringWriter();
    command.setShellOutput(new java.io.PrintWriter(text));
    var cli = new picocli.CommandLine(command);
    assertEquals(0, cli.execute("delivery", item.id()));
    assertTrue(text.toString().contains("NOT_REQUESTED"));
    assertEquals(0, cli.execute("deliver", item.id()));
    assertEquals(0, calls.get());
    response.set(500);
    delivery.dispatchOne();
    assertEquals(2, cli.execute("retry-delivery", item.id()));
    assertEquals(0, cli.execute("retry-delivery", item.id(), "--acknowledge-duplicate-risk"));
    assertEquals("PENDING", delivery.status("project", item.id()).status());
  }

  /** 検証対象に必要な依存先と設定を組み立てるテスト用構成。 */
  @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
  @org.springframework.web.servlet.config.annotation.EnableWebMvc
  @org.springframework.context.annotation.Import({
    dev.mikoto2000.rei.web.SecurityConfig.class,
    dev.mikoto2000.rei.web.AttentionController.class,
    dev.mikoto2000.rei.web.ApiExceptionHandler.class
  })
  static class WebConfig {
    /** {@code apiKeyProperties} の条件を再現するための補助情報を返す。 */
    @org.springframework.context.annotation.Bean
    dev.mikoto2000.rei.web.ApiKeyProperties apiKeyProperties() {
      return new dev.mikoto2000.rei.web.ApiKeyProperties("secret");
    }
  }

  /** 注意の管理で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void httpControlsAuthenticateProjectOwnershipAndOnlyQueueTheExplicitRequest() {
    start(properties(true, false), true);
    var item = fact("api");
    new org.springframework.boot.test.context.runner.WebApplicationContextRunner()
        .withPropertyValues("rei.web.enabled=true", "rei.api-key=secret")
        .withBean(AttentionRepository.class, () -> inbox)
        .withBean(AttentionDeliveryService.class, () -> delivery)
        .withUserConfiguration(WebConfig.class)
        .run(
            context -> {
              var mvc =
                  org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(
                          context)
                      .addFilters(
                          context.getBean(
                              "springSecurityFilterChain", jakarta.servlet.Filter.class))
                      .build();
              String path = "/api/v1/projects/project/attention/" + item.id() + "/delivery";
              mvc.perform(
                      org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                          .contentType("application/json")
                          .content("{\"retry\":false,\"acknowledgeDuplicateRisk\":false}"))
                  .andExpect(
                      org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                          .isUnauthorized());
              mvc.perform(
                      org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)
                          .header("Authorization", "Bearer secret"))
                  .andExpect(
                      org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                              "$.status")
                          .value("NOT_REQUESTED"));
              mvc.perform(
                      org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                              path.replace("/projects/project/", "/projects/other/"))
                          .header("Authorization", "Bearer secret")
                          .contentType("application/json")
                          .content("{\"retry\":false,\"acknowledgeDuplicateRisk\":false}"))
                  .andExpect(
                      org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                          .isBadRequest());
              mvc.perform(
                      org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                          .header("Authorization", "Bearer secret")
                          .contentType("application/json")
                          .content("{\"retry\":false,\"acknowledgeDuplicateRisk\":false}"))
                  .andExpect(
                      org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                          .isOk())
                  .andExpect(
                      org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                              "$.status")
                          .value("PENDING"));
              assertEquals(0, calls.get());
            });
  }

  /** 注意の管理で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void explicitBindingAndBlankDefaultRemainDisabled() {
    var source =
        new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(
            Map.of(
                "rei.attention.delivery.enabled",
                "true",
                "rei.attention.delivery.automatic",
                "true",
                "rei.attention.delivery.endpoint",
                properties(true, true).endpoint(),
                "rei.attention.delivery.projects",
                "project,other"));
    var bound =
        new org.springframework.boot.context.properties.bind.Binder(source)
            .bind(
                "rei.attention.delivery",
                org.springframework.boot.context.properties.bind.Bindable.of(
                    AttentionDeliveryProperties.class))
            .get();
    assertTrue(bound.enabled());
    assertTrue(bound.automatic());
    assertEquals(Set.of("project", "other"), bound.projects());
    var blank =
        new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(
            Map.of(
                "rei.attention.delivery.enabled",
                "false",
                "rei.attention.delivery.projects",
                "",
                "rei.attention.delivery.endpoint",
                "",
                "rei.attention.delivery.bearer-token",
                ""));
    var defaults =
        new org.springframework.boot.context.properties.bind.Binder(blank)
            .bind(
                "rei.attention.delivery",
                org.springframework.boot.context.properties.bind.Bindable.of(
                    AttentionDeliveryProperties.class))
            .get();
    assertFalse(defaults.enabled());
    assertFalse(defaults.automatic());
    assertTrue(defaults.projects().isEmpty());
  }

  /** 注意の管理で、時刻や期限に応じて、待機・実行・保存の扱いを切り替えることを確認する。 */
  @Test
  void redirectIsNotFollowedAndRealTimeoutLeavesAnUnknownAttempt() throws Exception {
    server.createContext(
        "/redirect",
        exchange -> {
          exchange.getResponseHeaders().add("Location", properties(true, true).endpoint());
          exchange.sendResponseHeaders(302, -1);
          exchange.close();
        });
    var redirect =
        new AttentionDeliveryProperties(
            true,
            true,
            properties(true, true).endpoint().replace("/notify", "/redirect"),
            null,
            Set.of("project"));
    start(redirect, true);
    var redirected = fact("redirect");
    delivery.dispatchOne();
    assertEquals("FAILED", delivery.status("project", redirected.id()).status());
    assertEquals("http_302", delivery.status("project", redirected.id()).reason());
    assertEquals(0, calls.get());
    delivery.close();
    var gate = new java.util.concurrent.CountDownLatch(1);
    var arrived = new java.util.concurrent.CountDownLatch(1);
    server.createContext(
        "/slow",
        exchange -> {
          arrived.countDown();
          try {
            gate.await(4, java.util.concurrent.TimeUnit.SECONDS);
            exchange.sendResponseHeaders(204, -1);
          } catch (Exception ignored) {
          } finally {
            exchange.close();
          }
        });
    start(
        new AttentionDeliveryProperties(
            true,
            true,
            properties(true, true).endpoint().replace("/notify", "/slow"),
            null,
            Set.of("project")),
        true);
    var slow = fact("slow");
    long begin = System.nanoTime();
    try {
      delivery.dispatchOne();
      assertTrue(arrived.await(1, java.util.concurrent.TimeUnit.SECONDS));
      assertEquals("UNKNOWN", delivery.status("project", slow.id()).status());
      assertTrue(System.nanoTime() - begin < Duration.ofSeconds(4).toNanos());
      delivery.dispatchOne();
      assertEquals(1, delivery.status("project", slow.id()).attempts());
    } finally {
      gate.countDown();
    }
  }
}
