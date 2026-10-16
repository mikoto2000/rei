package dev.mikoto2000.rei.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.application.run.ChatSubmitService;
import dev.mikoto2000.rei.application.run.RunRegistry;
import dev.mikoto2000.rei.application.run.RunService;
import dev.mikoto2000.rei.application.run.RunStatus;
import dev.mikoto2000.rei.application.run.SessionBusyException;
import dev.mikoto2000.rei.application.run.SessionRegistry;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.ConversationInputRouter;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.event.AgentEventBus;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.InMemoryAgentEventBus;
import dev.mikoto2000.rei.launcher.BackendEndpoint;
import dev.mikoto2000.rei.launcher.BackendEndpointStore;
import dev.mikoto2000.rei.launcher.BackendOwnership;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Tag("integration")
class ConversationAdmissionIntegrationTest {
  @TempDir Path root;
  static final com.fasterxml.jackson.databind.ObjectMapper JSON =
      new com.fasterxml.jackson.databind.ObjectMapper();

  @Configuration(proxyBeanMethods = false)
  @Import({
    BackendInstanceConfiguration.class,
    InstanceController.class,
    BackendStopController.class,
    ChatController.class,
    RunController.class,
    ApiExceptionHandler.class,
    SecurityConfig.class,
    dev.mikoto2000.rei.conversation.SessionHistoryConfiguration.class,
    dev.mikoto2000.rei.application.run.MemoryRunSubmitService.class,
    SleepController.class
  })
  @ImportAutoConfiguration({
    org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration
        .class,
    org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration.class,
    org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration.class,
    org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration.class
  })
  static class Config {
    @Bean
    dev.mikoto2000.rei.llm.capture.CaptureStore captures() {
      return new dev.mikoto2000.rei.llm.capture.CaptureStore();
    }

    @Bean
    Clock clock() {
      return Clock.systemUTC();
    }

    @Bean
    dev.mikoto2000.rei.memory.service.MemoryEvents memoryEvents() {
      return org.mockito.Mockito.mock(dev.mikoto2000.rei.memory.service.MemoryEvents.class);
    }

    @Bean
    ProjectRegistry projects(
        @org.springframework.beans.factory.annotation.Value("${rei.data-dir}") String root) {
      return new ProjectRegistry(Path.of(root, "projects.json"));
    }

    @Bean
    AgentEventBus bus() {
      return new InMemoryAgentEventBus();
    }

    @Bean
    AgentEventFactory events(Clock clock) {
      return new AgentEventFactory(clock);
    }

    @Bean
    Queue<Runnable> pending() {
      return new java.util.concurrent.ConcurrentLinkedQueue<>();
    }

    @Bean
    ConversationInputRouter router(
        Queue<Runnable> pending, AgentEventBus bus, AgentEventFactory events) {
      return new ConversationInputRouter(
          pending::add,
          (context, prompt, queue) ->
              bus.publish(events.runCompleted(context.runId(), 0).withOwnership(context)));
    }

    @Bean
    RunRegistry runs(Clock clock) {
      return new RunRegistry(clock);
    }

    @Bean
    SessionRegistry sessions(Clock clock) {
      return new SessionRegistry(clock);
    }

    @Bean
    dev.mikoto2000.rei.core.service.CommandCancellationService cancellation() {
      return new dev.mikoto2000.rei.core.service.CommandCancellationService();
    }

    @Bean
    RunService runService(
        RunRegistry runs,
        AgentEventBus bus,
        AgentEventFactory events,
        dev.mikoto2000.rei.core.service.CommandCancellationService cancellation,
        ConversationInputRouter router) {
      return new RunService(runs, bus, events, cancellation, router::cancelQueued);
    }

    @Bean
    ChatSubmitService chat(
        ProjectRegistry projects,
        SessionRegistry sessions,
        RunRegistry runs,
        RunService service,
        ConversationInputRouter router,
        dev.mikoto2000.rei.application.session.SessionLifecycle lifecycle) {
      return new ChatSubmitService(
          projects,
          sessions,
          runs,
          lifecycle,
          (context, prompt) ->
              router.submit(context, prompt, work -> service.execute(context, work)),
          true);
    }

    @Bean
    dev.mikoto2000.rei.memory.service.SleepService sleep() {
      var service = org.mockito.Mockito.mock(dev.mikoto2000.rei.memory.service.SleepService.class);
      org.mockito.Mockito.when(
              service.sleep(
                  org.mockito.ArgumentMatchers.anyString(),
                  org.mockito.ArgumentMatchers.anyString(),
                  org.mockito.ArgumentMatchers.anyBoolean()))
          .thenAnswer(
              call ->
                  new dev.mikoto2000.rei.memory.service.SleepService.Report(
                      new dev.mikoto2000.rei.memory.service.SleepRun(
                          "sleep",
                          call.getArgument(0),
                          call.getArgument(1),
                          "start",
                          "end",
                          "PREVIEW",
                          0,
                          0,
                          0,
                          0,
                          0,
                          0,
                          0,
                          0,
                          0,
                          0,
                          0),
                      List.of(),
                      call.getArgument(2)));
      return service;
    }

    @Bean
    dev.mikoto2000.rei.memory.command.MemoryCommandSupport memorySupport() {
      var support =
          org.mockito.Mockito.mock(dev.mikoto2000.rei.memory.command.MemoryCommandSupport.class);
      org.mockito.Mockito.when(support.report(org.mockito.ArgumentMatchers.any()))
          .thenReturn("Sleep Preview");
      return support;
    }
  }

  @Test
  void receiptReplayCannotConsumeAnotherClientsCaptureReservation() throws Exception {
    try (var context = start();
        var http = HttpClient.newHttpClient()) {
      var endpoint = context.getBean(BackendInstance.class).get();
      var project =
          context
              .getBean(ProjectRegistry.class)
              .resolve(Files.createDirectory(root.resolve("project")));
      var lifecycle =
          context.getBean(dev.mikoto2000.rei.application.session.SessionLifecycle.class);
      var session = lifecycle.create(project, "capture");
      var captures = context.getBean(dev.mikoto2000.rei.llm.capture.CaptureStore.class);
      String firstClient = UUID.randomUUID().toString(), otherClient = UUID.randomUUID().toString();
      captures.reserve("cli:" + firstClient, session.sessionId());
      String body =
          JSON.writeValueAsString(
              Map.of(
                  "projectId",
                  project.id(),
                  "sessionId",
                  session.sessionId(),
                  "message",
                  "capture"));
      var original = post(endpoint, "/api/v1/chat", body, "capture-key");
      var withIdentity =
          HttpRequest.newBuilder(original, (header, value) -> true)
              .header("X-Rei-Client-Id", firstClient)
              .build();
      var accepted = http.send(withIdentity, HttpResponse.BodyHandlers.ofString());
      assertEquals(202, accepted.statusCode());
      String run = JSON.readTree(accepted.body()).path("runId").asText();
      assertNotNull(captures.logicalCall(run, null));
      captures.finish(run, "COMPLETED");
      captures.reserve("cli:" + otherClient, session.sessionId());
      var replay =
          HttpRequest.newBuilder(original, (header, value) -> true)
              .header("X-Rei-Client-Id", otherClient)
              .build();
      assertEquals(accepted.body(), http.send(replay, HttpResponse.BodyHandlers.ofString()).body());
      assertTrue(captures.status("cli:" + otherClient, session.sessionId()).startsWith("RESERVED"));
      assertEquals(1, context.getBean("pending", Queue.class).size());
    }
  }

  private org.springframework.context.ConfigurableApplicationContext start() {
    var application = new SpringApplication(Config.class);
    WebApplication.configure(application, "fixture-secret");
    return application.run(
        "--rei.data-dir=" + root,
        "--server.port=0",
        "--rei.conversation.concurrent-enabled=true",
        "--logging.config=classpath:web-test-logback.xml");
  }

  private HttpRequest request(BackendEndpoint endpoint, String path) {
    return HttpRequest.newBuilder(endpoint.uri().resolve(path))
        .header("Authorization", "Bearer fixture-secret")
        .header("Content-Type", "application/json")
        .build();
  }

  private HttpRequest post(BackendEndpoint endpoint, String path, String body, String key) {
    var builder =
        HttpRequest.newBuilder(endpoint.uri().resolve(path))
            .header("Authorization", "Bearer fixture-secret")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
    if (key != null) builder.header("Idempotency-Key", key);
    return builder.build();
  }

  @Test
  void sessionBoundOperationRetainsTheConversationLeaseUntilExecutionCleanup() throws Exception {
    try (var context = start()) {
      var project =
          context
              .getBean(ProjectRegistry.class)
              .resolve(Files.createDirectory(root.resolve("project")));
      var lifecycle =
          context.getBean(dev.mikoto2000.rei.application.session.SessionLifecycle.class);
      var session = lifecycle.create(project, "memory maintenance");
      var operation =
          new AgentRunContext(
              UUID.randomUUID().toString(),
              session.sessionId(),
              project.root(),
              project.id(),
              AgentRunContext.RequestSource.WEB);
      var runs = context.getBean(RunService.class);
      var registry = context.getBean(RunRegistry.class);
      registry.register(operation);
      var chat = context.getBean(ChatSubmitService.class);
      var router = context.getBean(ConversationInputRouter.class);
      router.submitOperation(
          operation,
          () -> {
            assertThrows(
                SessionBusyException.class,
                () -> chat.submit("during operation", project.id(), session.sessionId()));
            context
                .getBean(AgentEventBus.class)
                .publish(
                    context
                        .getBean(AgentEventFactory.class)
                        .runCompleted(operation.runId(), 0)
                        .withOwnership(operation));
            assertThrows(
                SessionBusyException.class,
                () ->
                    chat.submit(
                        "after terminal event before cleanup", project.id(), session.sessionId()));
          },
          work -> runs.execute(operation, work));
      assertThrows(
          SessionBusyException.class,
          () -> chat.submit("queued operation", project.id(), session.sessionId()));
      var pending = context.getBean("pending", Queue.class);
      ((Runnable) pending.remove()).run();
      assertEquals(RunStatus.COMPLETED, runs.get(operation.runId()).status());
      assertNotNull(chat.submit("after cleanup", project.id(), session.sessionId()));
    }
  }

  @Test
  void memoryHttpReceiptReplaysWithoutAnotherOperationOrConversationTurn() throws Exception {
    try (var context = start();
        var http = HttpClient.newHttpClient()) {
      var endpoint = context.getBean(BackendInstance.class).get();
      var project =
          context
              .getBean(ProjectRegistry.class)
              .resolve(Files.createDirectory(root.resolve("project")));
      var lifecycle =
          context.getBean(dev.mikoto2000.rei.application.session.SessionLifecycle.class);
      var session = lifecycle.create(project, "memory");
      String path = "/api/v1/projects/" + project.id() + "/memory/sleep",
          body = JSON.writeValueAsString(Map.of("sessionId", session.sessionId(), "preview", true));
      var first =
          http.send(
              post(endpoint, path, body, "memory-receipt"), HttpResponse.BodyHandlers.ofString());
      assertEquals(202, first.statusCode());
      assertEquals(
          first.body(),
          http.send(
                  post(endpoint, path, body, "memory-receipt"),
                  HttpResponse.BodyHandlers.ofString())
              .body());
      assertEquals(
          409,
          http.send(
                  post(endpoint, path, body.replace("true", "false"), "memory-receipt"),
                  HttpResponse.BodyHandlers.ofString())
              .statusCode());
      String continued =
          JSON.writeValueAsString(
              Map.of(
                  "projectId", project.id(), "sessionId", session.sessionId(), "message", "chat"));
      assertEquals(
          409,
          http.send(
                  post(endpoint, "/api/v1/chat", continued, "busy-chat"),
                  HttpResponse.BodyHandlers.ofString())
              .statusCode());
      var pending = context.getBean("pending", Queue.class);
      assertEquals(1, pending.size());
      ((Runnable) pending.remove()).run();
      assertTrue(pending.isEmpty());
      String run = JSON.readTree(first.body()).path("runId").asText();
      assertEquals(RunStatus.COMPLETED, context.getBean(RunService.class).get(run).status());
      assertTrue(
          context
              .getBean(dev.mikoto2000.rei.application.session.SessionQueryService.class)
              .listTurns(session.sessionId(), 10, null)
              .items()
              .isEmpty());
      assertEquals(
          first.body(),
          http.send(
                  post(endpoint, path, body, "memory-receipt"),
                  HttpResponse.BodyHandlers.ofString())
              .body());
      assertTrue(pending.isEmpty());
      var receipt =
          http.send(
              request(endpoint, "/api/v1/chat/receipts/memory-receipt"),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, receipt.statusCode());
      assertEquals(run, JSON.readTree(receipt.body()).path("runId").asText());
    }
  }

  @Test
  void realHttpConcurrentKeysBusySessionsAndRestartNeverDoubleDispatch() throws Exception {
    String run, session, project;
    try (var http = HttpClient.newHttpClient()) {
      try (var context = start()) {
        var endpoint = context.getBean(BackendInstance.class).get();
        project =
            context
                .getBean(ProjectRegistry.class)
                .resolve(Files.createDirectory(root.resolve("project")))
                .id();
        String body =
            JSON.writeValueAsString(
                Map.of("projectId", project, "message", "hello", "mode", "CONVERSATION"));
        var first =
            http.sendAsync(
                post(endpoint, "/api/v1/chat", body, "same"), HttpResponse.BodyHandlers.ofString());
        var second =
            http.sendAsync(
                post(endpoint, "/api/v1/chat", body, "same"), HttpResponse.BodyHandlers.ofString());
        var accepted = first.get();
        assertEquals(202, accepted.statusCode());
        assertEquals(202, second.get().statusCode());
        assertEquals(accepted.body(), second.get().body());
        var value = JSON.readTree(accepted.body());
        run = value.path("runId").asText();
        session = value.path("sessionId").asText();
        assertEquals(1, context.getBean("pending", Queue.class).size());
        var changed =
            http.send(
                post(endpoint, "/api/v1/chat", body.replace("hello", "changed"), "same"),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(409, changed.statusCode());
        String continued =
            JSON.writeValueAsString(
                Map.of(
                    "projectId",
                    project,
                    "sessionId",
                    session,
                    "message",
                    "next",
                    "mode",
                    "CONVERSATION"));
        var busy =
            http.send(
                post(endpoint, "/api/v1/chat", continued, "new"),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(409, busy.statusCode());
        assertEquals(run, JSON.readTree(busy.body()).path("runId").asText());
        var cancelled =
            http.send(
                post(endpoint, "/api/v1/runs/" + run + "/cancel", "{}", null),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(202, cancelled.statusCode());
        assertEquals("CANCELLED", context.getBean(RunService.class).get(run).status().name());
        var newRun =
            http.send(
                post(endpoint, "/api/v1/chat", continued, "next"),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(202, newRun.statusCode());
      }
      try (var restarted = start()) {
        var endpoint = restarted.getBean(BackendInstance.class).get();
        var receipt =
            http.send(
                request(endpoint, "/api/v1/chat/receipts/same"),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, receipt.statusCode());
        assertEquals(run, JSON.readTree(receipt.body()).path("runId").asText());
        String body =
            JSON.writeValueAsString(
                Map.of("projectId", project, "message", "hello", "mode", "CONVERSATION"));
        assertEquals(
            202,
            http.send(
                    post(endpoint, "/api/v1/chat", body, "same"),
                    HttpResponse.BodyHandlers.ofString())
                .statusCode());
        assertTrue(restarted.getBean("pending", Queue.class).isEmpty());
      }
    }
  }

  @Test
  void identityMismatchCannotStopBackendAndAcknowledgedStopReleasesLease() throws Exception {
    try (var context = start();
        var http = HttpClient.newHttpClient()) {
      var endpoint = context.getBean(BackendInstance.class).get();
      assertEquals(
          409,
          http.send(
                  post(
                      endpoint,
                      "/api/v1/instance/stop",
                      JSON.writeValueAsString(
                          Map.of(
                              "instanceId",
                              UUID.randomUUID().toString(),
                              "storageId",
                              endpoint.storageId())),
                      null),
                  HttpResponse.BodyHandlers.ofString())
              .statusCode());
      assertTrue(context.isActive());
      assertEquals(
          202,
          http.send(
                  post(
                      endpoint,
                      "/api/v1/instance/stop",
                      JSON.writeValueAsString(
                          Map.of(
                              "instanceId",
                              endpoint.instanceId(),
                              "storageId",
                              endpoint.storageId())),
                      null),
                  HttpResponse.BodyHandlers.ofString())
              .statusCode());
      long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
      while (BackendOwnership.isOwned(root) && System.nanoTime() < deadline) Thread.sleep(25);
      assertFalse(BackendOwnership.isOwned(root));
      assertTrue(new BackendEndpointStore(root).read().isEmpty());
    }
  }
}
