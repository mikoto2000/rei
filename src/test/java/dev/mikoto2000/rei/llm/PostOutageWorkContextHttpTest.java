package dev.mikoto2000.rei.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import dev.mikoto2000.rei.application.session.SessionMetadata;
import dev.mikoto2000.rei.application.session.SessionRepository;
import dev.mikoto2000.rei.conversation.ConversationTurnStore;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.project.ProjectContext;
import dev.mikoto2000.rei.core.project.ProjectService;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.ProjectAgentEventStore;
import dev.mikoto2000.rei.workcontext.LlmWorkContextExtractor;
import dev.mikoto2000.rei.workcontext.WorkContextGit;
import dev.mikoto2000.rei.workcontext.WorkContextProperties;
import dev.mikoto2000.rei.workcontext.WorkContextRepository;
import dev.mikoto2000.rei.workcontext.WorkContextService;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.prompt.Prompt;
import org.sqlite.SQLiteDataSource;

/** Actual SDK requests and isolated persistence exercise the parent-outage handoff. */
@Tag("integration")
class PostOutageWorkContextHttpTest {
  @TempDir Path root;

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void workContextWaitsForRecoveryButNeverExtendsItsBatchDeadline(boolean exceedsDeadline)
      throws Exception {
    var requests = new AtomicInteger();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          if (requests.incrementAndGet() == 1) {
            byte[] error =
                "{\"error\":{\"message\":\"synthetic outage\",\"type\":\"server_error\"}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(503, error.length);
            try (var body = exchange.getResponseBody()) {
              body.write(error);
            }
          } else {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            String value =
                new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString("{\"changes\":[]}");
            String chunks =
                "data: {\"id\":\"chat\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"test\",\"choices\":[{\"index\":0,\"delta\":{\"content\":"
                    + value
                    + "},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n";
            try (var body = exchange.getResponseBody()) {
              body.write(chunks.getBytes(StandardCharsets.UTF_8));
            }
          }
        });
    server.start();
    try {
      var properties = new LlmProperties();
      properties.setOutageCooldown(
          exceedsDeadline ? Duration.ofSeconds(5) : Duration.ofMillis(100));
      var connection = new LlmProperties.Server();
      connection.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
      connection.setModel("test");
      connection.setFallbackToDefault(false);
      properties.setFeature("chat", connection);
      properties.setFeature("memory", connection);
      var models =
          new LlmModelProvider(
              mock(org.springframework.ai.chat.model.ChatModel.class),
              properties,
              new AgentEventFactory(Clock.systemUTC()),
              event -> {});
      var source = new SQLiteDataSource();
      source.setUrl("jdbc:sqlite:" + root.resolve("work.db"));
      var repository = new WorkContextRepository(source);
      var sessions = mock(SessionRepository.class);
      var projects = mock(ProjectService.class);
      String projectId = "f32eb7c5-0d16-4c9c-99f1-200468f5d96e";
      String sessionId = "58fb1425-df2d-485c-b5e0-038b1a630a39";
      var project = new ProjectContext(projectId, "test", root);
      when(projects.registeredProjects()).thenReturn(List.of(project));
      when(sessions.findById(sessionId))
          .thenReturn(
              Optional.of(
                  new SessionMetadata(
                      sessionId, projectId, "synthetic", Instant.now(), Instant.now())));
      var turns = ConversationTurnStore.inMemory();
      var owner = new AgentRunContext("run", sessionId, root, projectId);
      turns.start(owner, "Synthetic project requirement");
      turns.finish(owner, ConversationTurnStore.Status.FAILED, "No completion verified");
      var budgets =
          new WorkContextProperties(
              true, true, 1200, 12000, 3, 20, 4000, 16, "memory", 0, 1, 4096, false);
      var service =
          new WorkContextService(
              repository,
              sessions,
              projects,
              turns,
              new ProjectAgentEventStore(root),
              new LlmWorkContextExtractor(models, budgets),
              new WorkContextGit(),
              budgets,
              Clock.systemUTC());
      assertThrows(
          RuntimeException.class,
          () ->
              models.chatModel("chat").stream(new Prompt("synthetic parent request")).blockLast());
      // A physical 503 is not retried invisibly by the SDK.
      assertEquals(1, requests.get());
      if (exceedsDeadline) {
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
            Duration.ofSeconds(3),
            () -> {
              var failure =
                  assertThrows(IllegalStateException.class, () -> service.update(sessionId, "run"));
              assertEquals(
                  "timeout",
                  dev.mikoto2000.rei.core.diagnostic.FailureDiagnostics.classify(failure).code());
            });
        assertTrue(repository.current(projectId).isEmpty());
        assertEquals(1, requests.get());
      } else {
        var saved = service.update(sessionId, "run").orElseThrow();
        assertEquals(1, saved.revision());
        assertTrue(saved.processedRuns().contains("run"));
        assertEquals(saved, repository.current(projectId).orElseThrow());
        assertEquals(2, requests.get());
        assertFalse(saved.processedRuns().isEmpty());
      }
    } finally {
      server.stop(0);
    }
  }
}
