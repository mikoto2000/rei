package dev.mikoto2000.rei.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
class LifeContextHttpTest {
  @TempDir Path root;

  @Configuration(proxyBeanMethods = false)
  @Import({
    WebApiIntegrationTest.Config.class,
    LifeContextController.class,
    dev.mikoto2000.rei.life.LifeContext.class,
    dev.mikoto2000.rei.storage.SqliteLifeContextStore.class
  })
  static class Config {}

  private SpringApplication app() {
    var app = new SpringApplication(Config.class);
    WebApplication.configure(app, "life-test-key");
    return app;
  }

  private String[] args() {
    return new String[] {
      "--rei.web.port=0",
      "--rei.data-dir=" + root,
      "--logging.config=classpath:web-test-logback.xml"
    };
  }

  private HttpResponse<String> send(
      HttpClient client, int port, String suffix, String body, boolean auth) throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/life/" + suffix))
            .timeout(Duration.ofSeconds(5));
    if (auth) request.header("Authorization", "Bearer life-test-key");
    if (body != null)
      request
          .header("Content-Type", "application/json")
          .PUT(HttpRequest.BodyPublishers.ofString(body));
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void authenticatedGlobalSettingsPersistWithoutEnablingSecretaryOrExecution() throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      try (var context = app().run(args())) {
        int port = Integer.parseInt(context.getEnvironment().getProperty("local.server.port"));
        assertEquals(401, send(client, port, "settings", null, false).statusCode());
        assertEquals(200, send(client, port, "status", null, true).statusCode());
        var current = send(client, port, "settings", null, true);
        assertEquals(200, current.statusCode());
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var node = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(current.body());
        node.put("workStart", "10:00");
        String body = "{\"settings\":" + node + "}";
        assertEquals(401, send(client, port, "settings", body, false).statusCode());
        assertEquals(200, send(client, port, "settings", body, true).statusCode());
        assertEquals(400, send(client, port, "settings", body, true).statusCode());
      }
      try (var context = app().run(args())) {
        assertEquals(
            "10:00",
            context.getBean(dev.mikoto2000.rei.life.LifeContext.class).settings().workStart());
      }
    }
  }
}
