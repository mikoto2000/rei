package dev.mikoto2000.rei.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.mikoto2000.rei.application.session.SecretaryIntake;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.secretary.SecretaryLedger;
import dev.mikoto2000.rei.storage.SqliteSecretaryDatabase;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Tag("integration")
class SecretaryAuthenticationTest {
  @TempDir Path root;

  @Configuration(proxyBeanMethods = false)
  @Import({
    WebApiIntegrationTest.Config.class,
    SecretaryLedger.class,
    SqliteSecretaryDatabase.class,
    SecretaryController.class,
    SecretaryFollowupController.class,
    SecretaryOrchestratorController.class,
    SecretaryAutonomyController.class,
    SecretaryConnectionsController.class,
    dev.mikoto2000.rei.secretary.SecretaryConnections.class,
    dev.mikoto2000.rei.secretary.SecretaryAutonomy.class,
    dev.mikoto2000.rei.secretary.SecretaryFollowups.class,
    dev.mikoto2000.rei.secretary.SecretaryGlobalBriefings.class,
    dev.mikoto2000.rei.secretary.SecretaryOrchestrator.class,
    SecretaryIntake.class
  })
  static class Config {}

  @Test
  void authenticatedConversationProducesCandidateAndDisabledApiIsAbsent() throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      try (var context = application().run(args(false))) {
        int port = Integer.parseInt(context.getEnvironment().getProperty("local.server.port"));
        assertEquals(
            404,
            send(client, port, "/api/v1/secretary/tasks?projectId=p", null, true).statusCode());
        assertEquals(
            404,
            send(client, port, "/api/v1/secretary/orchestrator/settings?projectId=p", null, true)
                .statusCode());
      }
      String project;
      try (var context = application().run(args(true))) {
        int port = Integer.parseInt(context.getEnvironment().getProperty("local.server.port"));
        project = context.getBean(ProjectRegistry.class).resolve(root).id();
        assertEquals(
            401, send(client, port, "/api/v1/secretary/briefing", null, false).statusCode());
        assertEquals(
            200, send(client, port, "/api/v1/secretary/briefing", null, true).statusCode());
        assertEquals(
            200,
            send(client, port, "/api/v1/secretary/briefings/settings", null, true).statusCode());
        assertEquals(
            200,
            send(
                    client,
                    port,
                    "/api/v1/secretary/briefings/generate",
                    "{\"key\":\"global-test\",\"review\":true}",
                    true)
                .statusCode());
        assertEquals(
            401,
            send(client, port, "/api/v1/secretary/preparation?projectId=" + project, null, false)
                .statusCode());
        assertEquals(
            200,
            send(client, port, "/api/v1/secretary/preparation?projectId=" + project, null, true)
                .statusCode());
        assertEquals(
            401,
            send(client, port, "/api/v1/secretary/briefings?projectId=" + project, null, false)
                .statusCode());
        assertEquals(
            200,
            send(
                    client,
                    port,
                    "/api/v1/secretary/briefings/generate",
                    "{\"projectId\":\"" + project + "\",\"key\":\"manual-test\",\"review\":true}",
                    true)
                .statusCode());
        assertEquals(
            400,
            send(
                    client,
                    port,
                    "/api/v1/secretary/briefings/generate",
                    "{\"projectId\":\"" + project + "\",\"key\":\"x\",\"unknown\":true}",
                    true)
                .statusCode());
        assertEquals(
            401,
            send(client, port, "/api/v1/secretary/connections?projectId=" + project, null, false)
                .statusCode());
        assertEquals(
            200,
            send(client, port, "/api/v1/secretary/connections?projectId=" + project, null, true)
                .statusCode());
        assertEquals(
            401,
            send(
                    client,
                    port,
                    "/api/v1/secretary/external-actions?projectId=" + project,
                    null,
                    false)
                .statusCode());
        assertEquals(
            200,
            send(
                    client,
                    port,
                    "/api/v1/secretary/external-actions?projectId=" + project,
                    null,
                    true)
                .statusCode());
        assertEquals(
            400,
            send(
                    client,
                    port,
                    "/api/v1/secretary/external-actions",
                    "{\"projectId\":\"p\",\"unknown\":true}",
                    true)
                .statusCode());
        assertEquals(
            401,
            send(
                    client,
                    port,
                    "/api/v1/secretary/autonomy/settings?projectId=" + project,
                    null,
                    false)
                .statusCode());
        assertEquals(
            200,
            send(
                    client,
                    port,
                    "/api/v1/secretary/autonomy/settings?projectId=" + project,
                    null,
                    true)
                .statusCode());
        assertEquals(
            401,
            send(
                    client,
                    port,
                    "/api/v1/secretary/orchestrator/settings?projectId=" + project,
                    null,
                    false)
                .statusCode());
        assertEquals(
            200,
            send(
                    client,
                    port,
                    "/api/v1/secretary/orchestrator/settings?projectId=" + project,
                    null,
                    true)
                .statusCode());
        assertEquals(
            401,
            send(client, port, "/api/v1/secretary/tasks?projectId=" + project, null, false)
                .statusCode());
        assertEquals(
            202,
            send(
                    client,
                    port,
                    "/api/v1/chat",
                    "{\"projectId\":\"" + project + "\",\"message\":\"来週レポートを作成したい\"}",
                    true)
                .statusCode());
        assertFalse(context.getBean(SecretaryLedger.class).list(project, 0, 10).isEmpty());
      }
      try (var context = application().run(args(true))) {
        assertEquals(1, context.getBean(SecretaryLedger.class).list(project, 0, 10).size());
      }
    }
  }

  private SpringApplication application() {
    var app = new SpringApplication(Config.class);
    WebApplication.configure(app, "secretary-integration-key");
    return app;
  }

  private String[] args(boolean enabled) {
    return new String[] {
      "--rei.web.port=0",
      "--rei.data-dir=" + root,
      "--rei.secretary.enabled=" + enabled,
      "--rei.secretary.extraction-enabled=true",
      "--logging.config=classpath:web-test-logback.xml"
    };
  }

  private HttpResponse<String> send(
      HttpClient client, int port, String path, String body, boolean auth) throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(5));
    if (auth) request.header("Authorization", "Bearer secretary-integration-key");
    if (body != null)
      request
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(body));
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }
}
