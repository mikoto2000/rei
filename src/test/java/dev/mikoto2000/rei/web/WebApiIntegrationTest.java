package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.event.*;
import java.nio.file.Path;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.context.annotation.*;
import static org.assertj.core.api.Assertions.*;

class WebApiIntegrationTest {
  @Configuration(proxyBeanMethods = false)
  @Import({WebApiConfiguration.class, SecurityConfig.class, ChatController.class, RunController.class,
      SseController.class, ProjectController.class, ApiExceptionHandler.class})
  @ImportAutoConfiguration({
      org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration.class,
      org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration.class,
      org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration.class,
      org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration.class,
      org.springframework.boot.actuate.autoconfigure.endpoint.EndpointAutoConfiguration.class,
      org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointAutoConfiguration.class,
      org.springframework.boot.actuate.autoconfigure.web.server.ManagementContextAutoConfiguration.class,
      org.springframework.boot.health.autoconfigure.contributor.HealthContributorAutoConfiguration.class,
      org.springframework.boot.health.autoconfigure.actuate.endpoint.HealthEndpointAutoConfiguration.class,
      org.springframework.boot.health.autoconfigure.registry.HealthContributorRegistryAutoConfiguration.class})
  static class Config {
    @Bean Clock clock() { return Clock.systemUTC(); }
    @Bean AgentEventFactory events(Clock clock) { return new AgentEventFactory(clock); }
    @Bean AgentEventBus bus() { return new InMemoryAgentEventBus(); }
    @Bean CommandCancellationService cancellation() { return new CommandCancellationService(); }
    @Bean(destroyMethod = "shutdownNow") ExecutorService executor() { return Executors.newVirtualThreadPerTaskExecutor(); }
    @Bean ConversationInputRouter router(ExecutorService executor, AgentEventBus bus, AgentEventFactory events) {
      return new ConversationInputRouter(executor, (context, prompt, input) -> {
        bus.publish(events.runStarted(context.runId(), "test", null).withOwnership(context));
        bus.publish(events.messageDelta("message", "hello").withOwnership(context));
        bus.publish(events.runCompleted(context.runId(), 1).withOwnership(context));
      });
    }
  }
  @TempDir Path directory;

  @Test void actualServerAuthenticatesChatStreamsReplayAndExposesOnlyHealth() throws Exception {
    var application = new SpringApplication(Config.class);
    WebApplication.configure(application, "integration-key");
    try (var context = application.run("--rei.web.port=0", "--rei.data-dir=" + directory,
        "--logging.config=classpath:web-test-logback.xml")) {
      int port = Integer.parseInt(context.getEnvironment().getProperty("local.server.port"));
      var project = context.getBean(ProjectRegistry.class).resolve(directory);
      try (var client = HttpClient.newHttpClient()) {
        var health = send(client, port, "/actuator/health", null, false);
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.body()).doesNotContain("components", "details", "exception");
        assertThat(send(client, port, "/api/v1/runs/unknown", null, false).statusCode()).isEqualTo(401);
        for (String path : new String[] {"/actuator/env", "/actuator/configprops", "/actuator/beans",
            "/api/v1/history", "/api/v1/sh", "/api/v1/project/cd"})
          assertThat(send(client, port, path, null, true).statusCode()).isEqualTo(404);
        assertThat(send(client, port, "/api/v1/projects", null, false).statusCode()).isEqualTo(401);
        var listed = send(client, port, "/api/v1/projects", null, true);
        assertThat(listed.statusCode()).isEqualTo(200);
        var projects = new com.fasterxml.jackson.databind.ObjectMapper().readTree(listed.body());
        String projectId = projects.get(0).get("id").asText();
        assertThat(projectId).isEqualTo(project.id());
        var accepted = send(client, port, "/api/v1/chat", "{\"message\":\"hello\",\"projectId\":\"" + projectId + "\"}", true);
        assertThat(accepted.statusCode()).isEqualTo(202);
        var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(accepted.body());
        String location = accepted.headers().firstValue("Location").orElseThrow();
        assertThat(location).isEqualTo("/api/v1/runs/" + json.get("runId").asText());
        assertThat(json.get("turnId")).isEqualTo(json.get("runId"));
        var stream = send(client, port, location + "/events", null, true);
        assertThat(stream.statusCode()).isEqualTo(200);
        assertThat(stream.body()).contains("event:agent.run.started", "event:message.delta", "event:agent.run.completed");
        assertThat(send(client, port, location, null, true).body()).contains("COMPLETED");
      }
    }
  }
  private HttpResponse<String> send(HttpClient client, int port, String path, String body, boolean authenticated) throws Exception {
    var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(10));
    if (authenticated) request.header("Authorization", "Bearer integration-key");
    if (body != null) request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }
}
