package dev.mikoto2000.rei.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mikoto2000.rei.autonomous.AutonomousActionProperties;
import dev.mikoto2000.rei.autonomous.AutonomousDraft;
import dev.mikoto2000.rei.autonomous.AutonomousDraftNotifications;
import dev.mikoto2000.rei.autonomous.AutonomousDraftStore;
import dev.mikoto2000.rei.autonomous.AutonomousGenerationService;
import dev.mikoto2000.rei.autonomous.AutonomousNotificationDispatcher;
import dev.mikoto2000.rei.autonomous.AutonomousNotificationStore;
import dev.mikoto2000.rei.autonomous.AutonomousPostRecord;
import dev.mikoto2000.rei.autonomous.AutonomousPublicSources;
import dev.mikoto2000.rei.autonomous.AutonomousPublicationService;
import dev.mikoto2000.rei.autonomous.AutonomousPublicationStore;
import dev.mikoto2000.rei.autonomous.AutonomousPublicationTransport;
import dev.mikoto2000.rei.storage.SqliteAutonomousDraftStore;
import dev.mikoto2000.rei.storage.SqliteAutonomousPublicSources;
import dev.mikoto2000.rei.storage.StorageDatabase;
import dev.mikoto2000.rei.storage.StorageMigrationCoordinator;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Tag("integration")
class AutonomousHttpSmokeTest {
  private static final String BASE = "/api/v1/autonomous";
  @TempDir Path root;
  private AutonomousDraft initial;

  private static Clock clock() {
    return Clock.fixed(Instant.parse("2026-11-28T03:00:00Z"), ZoneOffset.UTC);
  }

  private static AutonomousActionProperties settings(boolean enabled, boolean bluesky) {
    var d = AutonomousActionProperties.Bluesky.defaults();
    return new AutonomousActionProperties(
        enabled,
        new AutonomousActionProperties.Bluesky(
            bluesky,
            d.cron(),
            d.zone(),
            1,
            1,
            d.cooldown(),
            d.draftExpiration(),
            3,
            d.regenerationCooldown(),
            d.systemPrompt()));
  }

  @Configuration(proxyBeanMethods = false)
  @Import({AutonomousController.class, SecurityConfig.class})
  @ImportAutoConfiguration({
    org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration
        .class,
    org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration.class,
    org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration.class,
    org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration.class
  })
  static class Config {
    @Bean
    AutonomousDraftNotifications notifications(@Value("${rei.data-dir}") String directory) {
      var inbox =
          new dev.mikoto2000.rei.attention.AttentionRepository(
              new org.springframework.jdbc.datasource.DriverManagerDataSource(
                  "jdbc:sqlite:" + Path.of(directory).resolve("attention.db")),
              clock());
      var bus = new dev.mikoto2000.rei.event.InMemoryAgentEventBus();
      return new dev.mikoto2000.rei.attention.AttentionService(inbox, bus, bus, clock(), () -> 0);
    }

    @Bean
    AutonomousNotificationDispatcher notificationDispatcher(
        AutonomousDraftStore drafts,
        org.springframework.beans.factory.ObjectProvider<AutonomousDraftNotifications>
            notifications) {
      return new AutonomousNotificationDispatcher(
          (AutonomousNotificationStore) drafts, notifications);
    }

    @Bean
    FixturePublisher publisher() {
      return new FixturePublisher();
    }

    @Bean
    AutonomousPublicationService publication(
        AutonomousDraftStore drafts, FixturePublisher publisher) {
      return new AutonomousPublicationService(
          (AutonomousPublicationStore) drafts, () -> publisher.allowed, publisher);
    }

    @Bean
    AutonomousDraftStore drafts(
        @Value("${rei.data-dir}") String directory,
        @Value("${rei.autonomous-action.enabled:false}") boolean enabled,
        @Value("${rei.autonomous-action.bluesky.enabled:false}") boolean bluesky) {
      return new SqliteAutonomousDraftStore(
          new StorageDatabase(Path.of(directory)), clock(), settings(enabled, bluesky));
    }

    @Bean
    AutonomousPublicSources sources(@Value("${rei.data-dir}") String directory) {
      return new SqliteAutonomousPublicSources(new StorageDatabase(Path.of(directory)), clock());
    }

    @Bean
    @ConditionalOnProperty(
        prefix = "rei.autonomous-action",
        name = {"enabled", "bluesky.enabled"},
        havingValue = "true")
    AutonomousGenerationService generation(
        AutonomousDraftStore drafts, AutonomousPublicSources sources) {
      return new AutonomousGenerationService(
          drafts,
          sources,
          () -> new AutonomousDraft.Target("did:plc:example", "rei.example"),
          snapshot ->
              "{\"decision\":\"PROPOSE_POST\",\"reason\":\"公開用の話題\",\"draft\":{\"text\":\"HTTPで生成した本文の全文\",\"sourceIds\":[\""
                  + snapshot.getFirst().id()
                  + "\"]}}");
    }
  }

  static final class FixturePublisher implements AutonomousPublicationTransport {
    private static final String CID = "bafyreidfayvfuwqa7qlnopdjiqrxzs6blmoeu4rujcjtnci5beludirz2a";
    boolean allowed = true, uncertain;
    int writes, reads;
    AutonomousPostRecord sent;

    public void validate(String text) {}

    public Receipt send(AutonomousPostRecord record, java.util.function.BooleanSupplier gate) {
      writes++;
      sent = record;
      return new Receipt(
          uncertain
              ? AutonomousPublicationStore.Outcome.UNKNOWN
              : AutonomousPublicationStore.Outcome.PUBLISHED,
          uncertain ? null : record.uri(),
          uncertain ? null : CID,
          uncertain ? "response_lost" : "");
    }

    public FoundRecord lookup(AutonomousPostRecord record) {
      reads++;
      return new FoundRecord(record.uri(), CID, record.recordJson());
    }
  }

  @BeforeEach
  void prepare() throws Exception {
    try (var migration = new StorageMigrationCoordinator(root)) {
      migration.prepare();
    }
    var database = new StorageDatabase(root);
    var sources = new SqliteAutonomousPublicSources(database, clock());
    var topic = sources.register("公開用の話題", true, "fixture");
    var drafts = new SqliteAutonomousDraftStore(database, clock(), settings(true, true));
    var claim =
        drafts
            .reserve("initial", AutonomousDraft.Trigger.MANUAL, "did:plc:example", "rei.example")
            .claim();
    initial =
        drafts
            .complete(claim, "最初の本文", "投稿理由", List.of("topic:" + topic.id() + ":1"))
            .orElseThrow();
  }

  private ConfigurableApplicationContext start(boolean enabled, Path directory) {
    var application = new SpringApplication(Config.class);
    application.setLogStartupInfo(false);
    WebApplication.configure(application, "autonomous-http-fixture-key");
    return application.run(
        "--rei.data-dir=" + directory,
        "--server.port=0",
        "--server.address=127.0.0.1",
        "--rei.autonomous-action.enabled=" + enabled,
        "--rei.autonomous-action.bluesky.enabled=" + enabled,
        "--logging.config=classpath:web-test-logback.xml",
        "--spring.main.banner-mode=off");
  }

  private HttpResponse<String> request(
      HttpClient client,
      ConfigurableApplicationContext context,
      String method,
      String path,
      Object body,
      boolean authenticated)
      throws Exception {
    int port = Integer.parseInt(context.getEnvironment().getProperty("local.server.port"));
    var builder =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + BASE + path))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json");
    if (authenticated) builder.header("Authorization", "Bearer autonomous-http-fixture-key");
    var payload =
        body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(StorageDatabase.JSON.writeValueAsString(body));
    return client.send(
        builder.method(method, payload).build(), HttpResponse.BodyHandlers.ofString());
  }

  private com.fasterxml.jackson.databind.JsonNode json(HttpResponse<String> response)
      throws Exception {
    return StorageDatabase.JSON.readTree(response.body());
  }

  @Test
  void notificationsAreAuthenticatedGlobalReferencesAndAcknowledgmentNeverApproves()
      throws Exception {
    try (var context = start(true, root);
        var client = HttpClient.newHttpClient()) {
      context.getBean(AutonomousNotificationDispatcher.class).flush();
      assertThat(request(client, context, "GET", "/notifications", null, false).statusCode())
          .isEqualTo(401);
      var list = request(client, context, "GET", "/notifications?limit=20", null, true);
      assertThat(list.statusCode()).isEqualTo(200);
      assertThat(json(list)).hasSize(1);
      var notice = json(list).get(0);
      assertThat(notice.path("draftId").asText()).isEqualTo(initial.id());
      assertThat(notice.path("status").asText()).isEqualTo("OPEN");
      assertThat(list.body()).doesNotContain("最初の本文", "投稿理由", "projectId", "sessionId", "runId");
      var path = "/notifications/" + notice.path("id").asText() + "/ack";
      assertThat(request(client, context, "POST", path, null, false).statusCode()).isEqualTo(401);
      assertThat(
              request(client, context, "POST", path, Map.of("approved", true), true).statusCode())
          .isEqualTo(400);
      assertThat(request(client, context, "POST", path, Map.of("text", "差し替え"), true).statusCode())
          .isEqualTo(400);
      var acknowledged = request(client, context, "POST", path, Map.of(), true);
      assertThat(acknowledged.statusCode()).isEqualTo(200);
      assertThat(json(acknowledged).path("status").asText()).isEqualTo("ACKNOWLEDGED");
      assertThat(json(request(client, context, "GET", "/notifications", null, true))).isEmpty();
      assertThat(context.getBean(AutonomousDraftStore.class).get(initial.id())).isEqualTo(initial);
      assertThat(context.getBean(FixturePublisher.class).writes).isZero();
      assertThat(request(client, context, "GET", "/notifications?limit=0", null, true).statusCode())
          .isEqualTo(400);
      assertThat(
              request(client, context, "GET", "/notifications?limit=201", null, true).statusCode())
          .isEqualTo(400);
      assertThat(
              request(
                      client,
                      context,
                      "POST",
                      "/notifications/" + java.util.UUID.randomUUID() + "/ack",
                      Map.of(),
                      true)
                  .statusCode())
          .isEqualTo(404);
    }
  }

  @Test
  void notificationsRemainReadableWhenGenerationIsDisabledAndResolveWithTheirDraft()
      throws Exception {
    try (var context = start(false, root);
        var client = HttpClient.newHttpClient()) {
      var worker = context.getBean(AutonomousNotificationDispatcher.class);
      worker.flush();
      var list = request(client, context, "GET", "/notifications", null, true);
      assertThat(list.statusCode()).isEqualTo(200);
      assertThat(json(list)).hasSize(1);
      assertThat(
              request(
                      client,
                      context,
                      "POST",
                      "/drafts/" + initial.id() + "/reject",
                      Map.of("expectedRevision", 1, "reason", "PRIVATE_FEEDBACK"),
                      true)
                  .statusCode())
          .isEqualTo(200);
      worker.flush();
      assertThat(json(request(client, context, "GET", "/notifications", null, true))).isEmpty();
      assertThat(context.getBean(FixturePublisher.class).writes).isZero();
    }
  }

  @Test
  void selectedPublicationUsesExistingAuthenticationAndOnlyTheReviewedRevision() throws Exception {
    try (var context = start(true, root);
        var client = HttpClient.newHttpClient()) {
      var publisher = context.getBean(FixturePublisher.class);
      var base = "/drafts/" + initial.id();
      assertThat(
              request(
                      client,
                      context,
                      "POST",
                      base + "/publish",
                      Map.of("expectedRevision", 1),
                      false)
                  .statusCode())
          .isEqualTo(401);
      for (var bad :
          List.of(
              Map.of("expectedRevision", 1, "text", "差し替え"),
              Map.of("expectedRevision", 1, "actor", "administrator"),
              Map.of("expectedRevision", 1, "did", "did:plc:other"),
              Map.of("expectedRevision", 0)))
        assertThat(request(client, context, "POST", base + "/publish", bad, true).statusCode())
            .isEqualTo(400);
      assertThat(
              request(
                      client,
                      context,
                      "POST",
                      base + "/publish",
                      Map.of("expectedRevision", 2),
                      true)
                  .statusCode())
          .isEqualTo(409);
      assertThat(publisher.writes).isZero();
      var published =
          request(client, context, "POST", base + "/publish", Map.of("expectedRevision", 1), true);
      assertThat(published.statusCode()).isEqualTo(200);
      assertThat(json(published).path("state").asText()).isEqualTo("PUBLISHED");
      assertThat(json(published).path("approval").path("approvedBy").asText())
          .isEqualTo("api-client");
      assertThat(publisher.sent.text()).isEqualTo(initial.content().text());
      assertThat(publisher.sent.did()).isEqualTo(initial.target().did());
      assertThat(
              request(
                      client,
                      context,
                      "POST",
                      base + "/publish",
                      Map.of("expectedRevision", 1),
                      true)
                  .statusCode())
          .isEqualTo(409);
      assertThat(publisher.writes).isEqualTo(1);
    }
  }

  @Test
  void unknownPublicationCanOnlyBeLookedUpAndExistingDenyRightsBlockPublishing() throws Exception {
    try (var context = start(true, root);
        var client = HttpClient.newHttpClient()) {
      var publisher = context.getBean(FixturePublisher.class);
      var base = "/drafts/" + initial.id();
      publisher.allowed = false;
      assertThat(
              request(
                      client,
                      context,
                      "POST",
                      base + "/publish",
                      Map.of("expectedRevision", 1),
                      true)
                  .statusCode())
          .isEqualTo(403);
      assertThat(publisher.writes).isZero();
      publisher.allowed = true;
      publisher.uncertain = true;
      assertThat(
              json(request(
                      client,
                      context,
                      "POST",
                      base + "/publish",
                      Map.of("expectedRevision", 1),
                      true))
                  .path("state")
                  .asText())
          .isEqualTo("UNKNOWN");
      assertThat(
              request(
                      client,
                      context,
                      "POST",
                      base + "/publish",
                      Map.of("expectedRevision", 1),
                      true)
                  .statusCode())
          .isEqualTo(409);
      assertThat(
              request(
                      client,
                      context,
                      "POST",
                      base + "/reconcile",
                      Map.of("expectedRevision", 1),
                      false)
                  .statusCode())
          .isEqualTo(401);
      var found =
          request(
              client, context, "POST", base + "/reconcile", Map.of("expectedRevision", 1), true);
      assertThat(found.statusCode()).isEqualTo(200);
      assertThat(json(found).path("state").asText()).isEqualTo("PUBLISHED");
      assertThat(publisher.writes).isEqualTo(1);
      assertThat(publisher.reads).isEqualTo(1);
    }
  }

  @Test
  void authenticationReviewEditingAndStaleRevisionUseTheRealHttpBoundary() throws Exception {
    try (var context = start(true, root);
        var client = HttpClient.newHttpClient()) {
      assertThat(request(client, context, "GET", "/drafts", null, false).statusCode())
          .isEqualTo(401);
      assertThat(
              request(
                      client,
                      context,
                      "POST",
                      "/drafts/" + initial.id() + "/edit",
                      Map.of("expectedRevision", 1, "text", "未認証の変更"),
                      false)
                  .statusCode())
          .isEqualTo(401);
      assertThat(request(client, context, "GET", "/drafts?limit=0", null, true).statusCode())
          .isEqualTo(400);
      assertThat(request(client, context, "GET", "/drafts/missing-draft", null, true).statusCode())
          .isEqualTo(404);
      var listing = request(client, context, "GET", "/drafts?limit=20", null, true);
      assertThat(listing.statusCode()).isEqualTo(200);
      assertThat(json(listing).get(0).path("id").asText()).isEqualTo(initial.id());
      var base = "/drafts/" + initial.id();
      var detail = request(client, context, "GET", base, null, true);
      assertThat(detail.statusCode()).isEqualTo(200);
      assertThat(json(detail).path("content").path("text").asText())
          .isEqualTo(initial.content().text());
      assertThat(json(detail).path("target").path("did").asText())
          .isEqualTo(initial.target().did());
      assertThat(json(detail).path("target").path("handle").asText())
          .isEqualTo(initial.target().handle());
      assertThat(json(detail).path("createdAt").asText()).isEqualTo(initial.createdAt().toString());
      assertThat(json(detail).path("expiresAt").asText()).isEqualTo(initial.expiresAt().toString());
      assertThat(json(detail).path("content").path("sourceIds").get(0).asText())
          .isEqualTo(initial.content().sourceIds().getFirst());
      var text = "HTTPで編集した\r\n本文の全文";
      var edited =
          request(
              client,
              context,
              "POST",
              base + "/edit",
              Map.of("expectedRevision", 1, "text", text),
              true);
      assertThat(edited.statusCode()).isEqualTo(200);
      assertThat(json(edited).path("content").path("text").asText()).isEqualTo(text);
      assertThat(json(edited).path("content").path("revision").asInt()).isEqualTo(2);
      var stale =
          request(
              client,
              context,
              "POST",
              base + "/edit",
              Map.of("expectedRevision", 1, "text", "PRIVATE_STALE_BODY"),
              true);
      assertThat(stale.statusCode()).isEqualTo(409);
      assertThat(stale.body()).doesNotContain("PRIVATE_STALE_BODY");
      assertThat(
              request(client, context, "POST", base + "/edit", Map.of("text", "revisionなし"), true)
                  .statusCode())
          .isEqualTo(400);
      assertThat(
              request(
                      client,
                      context,
                      "POST",
                      base + "/edit",
                      Map.of("expectedRevision", 2, "text", "変更", "approval", true),
                      true)
                  .statusCode())
          .isEqualTo(400);
      assertThat(context.getBean(AutonomousDraftStore.class).get(initial.id()).content().text())
          .isEqualTo(text);
    }
  }

  @Test
  void regenerationReturnsTheWholeNewBodyAndHasNoPublicationRoutes() throws Exception {
    try (var context = start(true, root);
        var client = HttpClient.newHttpClient()) {
      var base = "/drafts/" + initial.id();
      var generated =
          request(
              client, context, "POST", base + "/regenerate", Map.of("expectedRevision", 1), true);
      assertThat(generated.statusCode()).isEqualTo(200);
      assertThat(json(generated).path("status").asText()).isEqualTo("DRAFT_REGENERATED");
      assertThat(json(generated).path("draft").path("content").path("text").asText())
          .isEqualTo("HTTPで生成した本文の全文");
      assertThat(json(generated).path("draft").path("content").path("revision").asInt())
          .isEqualTo(2);
      for (var action : List.of("approve"))
        assertThat(
                request(
                        client,
                        context,
                        "POST",
                        base + "/" + action,
                        Map.of("expectedRevision", 2),
                        true)
                    .statusCode())
            .isEqualTo(404);
      assertThat(
              request(
                      client,
                      context,
                      "POST",
                      base + "/regenerate",
                      Map.of("expectedRevision", 2, "approved", true),
                      true)
                  .statusCode())
          .isEqualTo(400);
      assertThat(context.getBean(AutonomousDraftStore.class).get(initial.id()).state())
          .isEqualTo(AutonomousDraft.State.PENDING_REVIEW);
    }
  }

  @Test
  void rejectionStaysPrivateAndTerminal() throws Exception {
    try (var context = start(true, root);
        var client = HttpClient.newHttpClient()) {
      var base = "/drafts/" + initial.id();
      var rejected =
          request(
              client,
              context,
              "POST",
              base + "/reject",
              Map.of("expectedRevision", 1, "reason", "PRIVATE_REJECTION"),
              true);
      assertThat(rejected.statusCode()).isEqualTo(200);
      assertThat(json(rejected).path("state").asText()).isEqualTo("REJECTED");
      assertThat(json(rejected).path("rejectionReasonPublic").asBoolean()).isFalse();
      assertThat(
              request(
                      client,
                      context,
                      "POST",
                      base + "/regenerate",
                      Map.of("expectedRevision", 1),
                      true)
                  .statusCode())
          .isEqualTo(409);
    }
  }

  @Test
  void publicTopicConsentUsesSeparateRevisionCheckedOperations() throws Exception {
    try (var context = start(true, root);
        var client = HttpClient.newHttpClient()) {
      var created = request(client, context, "POST", "/sources", Map.of("text", "確認中の話題"), true);
      assertThat(created.statusCode()).isEqualTo(200);
      assertThat(json(created).path("publicUseAllowed").asBoolean()).isFalse();
      assertThat(json(created).path("actor").asText()).isEqualTo("api");
      var base = "/sources/" + json(created).path("id").asText();
      assertThat(
              request(
                      client,
                      context,
                      "POST",
                      base + "/permit",
                      Map.of("expectedRevision", 1),
                      true)
                  .statusCode())
          .isEqualTo(200);
      var edited =
          request(
              client,
              context,
              "POST",
              base + "/edit",
              Map.of("expectedRevision", 2, "text", "編集した話題"),
              true);
      assertThat(edited.statusCode()).isEqualTo(200);
      assertThat(json(edited).path("publicUseAllowed").asBoolean()).isFalse();
      assertThat(json(edited).path("revision").asInt()).isEqualTo(3);
      assertThat(
              request(
                      client,
                      context,
                      "POST",
                      base + "/revoke",
                      Map.of("expectedRevision", 2),
                      true)
                  .statusCode())
          .isEqualTo(409);
      assertThat(
              request(
                      client,
                      context,
                      "POST",
                      base + "/permit",
                      Map.of("expectedRevision", 3),
                      true)
                  .statusCode())
          .isEqualTo(200);
      assertThat(
              request(
                      client,
                      context,
                      "POST",
                      base + "/revoke",
                      Map.of("expectedRevision", 4),
                      true)
                  .statusCode())
          .isEqualTo(200);
      assertThat(json(request(client, context, "GET", base, null, true)).path("revision").asInt())
          .isEqualTo(5);
      assertThat(json(request(client, context, "GET", "/sources?limit=20", null, true)).size())
          .isEqualTo(2);
      assertThat(
              request(
                      client,
                      context,
                      "POST",
                      "/sources",
                      Map.of("text", "話題", "actor", "human"),
                      true)
                  .statusCode())
          .isEqualTo(400);
    }
  }

  @Test
  void disabledGenerationStillAllowsViewingAndEditingSavedDrafts() throws Exception {
    try (var context = start(false, root);
        var client = HttpClient.newHttpClient()) {
      var base = "/drafts/" + initial.id();
      assertThat(request(client, context, "GET", base, null, true).statusCode()).isEqualTo(200);
      assertThat(
              json(request(client, context, "POST", "/drafts/generate", null, true))
                  .path("reason")
                  .asText())
          .isEqualTo("disabled");
      var regeneration =
          request(
              client, context, "POST", base + "/regenerate", Map.of("expectedRevision", 1), true);
      assertThat(json(regeneration).path("reason").asText()).isEqualTo("disabled");
      assertThat(json(regeneration).path("draft").path("id").asText()).isEqualTo(initial.id());
      assertThat(
              request(
                      client,
                      context,
                      "POST",
                      base + "/edit",
                      Map.of("expectedRevision", 1, "text", "確認して編集"),
                      true)
                  .statusCode())
          .isEqualTo(200);
    }
  }

  @Test
  void manualGenerationReturnsTheSavedUnapprovedBody() throws Exception {
    var empty = root.resolve("empty");
    try (var migration = new StorageMigrationCoordinator(empty)) {
      migration.prepare();
    }
    try (var context = start(true, empty);
        var client = HttpClient.newHttpClient()) {
      assertThat(
              request(
                      client,
                      context,
                      "POST",
                      "/sources",
                      Map.of("text", "公開用の話題", "publicUseAllowed", true),
                      true)
                  .statusCode())
          .isEqualTo(200);
      assertThat(
              request(client, context, "POST", "/drafts/generate", Map.of("approval", true), true)
                  .statusCode())
          .isEqualTo(400);
      var generated = request(client, context, "POST", "/drafts/generate", null, true);
      assertThat(generated.statusCode()).isEqualTo(200);
      assertThat(json(generated).path("status").asText()).isEqualTo("DRAFT_CREATED");
      assertThat(json(generated).path("draft").path("content").path("text").asText())
          .isEqualTo("HTTPで生成した本文の全文");
      assertThat(json(generated).path("draft").path("state").asText()).isEqualTo("PENDING_REVIEW");
      assertThat(json(generated).path("draft").path("publication").isNull()).isTrue();
    }
  }
}
