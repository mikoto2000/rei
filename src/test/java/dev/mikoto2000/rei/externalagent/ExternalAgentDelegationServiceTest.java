package dev.mikoto2000.rei.externalagent;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.AgentRunScope;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.core.stagnation.ProgressEvaluator;
import dev.mikoto2000.rei.core.stagnation.RunExecutionContext;
import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventType;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@org.junit.jupiter.api.Tag("integration")
class ExternalAgentDelegationServiceTest {
  @TempDir Path root;

  RunExecutionContext run(String prompt) {
    var result =
        new RunExecutionContext(
            "run",
            new OutputLimitRunBudget(2, 10),
            new ProgressEvaluator(root, null),
            new AgentEventFactory(Clock.systemUTC()),
            e -> {});
    result.setRunContext(new AgentRunContext("run", "conversation", root, "project"));
    result.setUserRequest(prompt);
    return result;
  }

  @Test
  void sharesOneRunBudgetAndPublishesOrderedCorrelatedEvents() {
    List<AgentEvent> events = new ArrayList<>();
    List<ExternalAgentRequest> requests = new ArrayList<>();
    var service =
        new ExternalAgentDelegationService(
            (request, cancelled) -> {
              requests.add(request);
              return new ExternalAgentResult(
                  ExternalAgentResult.Status.SUCCESS,
                  "No issues",
                  List.of(),
                  List.of(),
                  12,
                  0,
                  "raw");
            },
            new CommandCancellationService(),
            new AgentEventFactory(Clock.systemUTC()),
            events::add,
            Optional.empty());
    var run = run("もう一回 Codex に依頼を出して、指摘を修正してください");
    assertTrue(service.review(run, "design", null, "decision").success());
    assertEquals(
        ExternalAgentResult.Status.REJECTED, service.review(run, "again", null, "").status());
    assertEquals(1, requests.size());
    assertEquals(root.toAbsolutePath(), requests.getFirst().projectRoot());
    assertEquals("run", requests.getFirst().runId());
    assertEquals(
        List.of(AgentEventType.DELEGATION_STARTED, AgentEventType.DELEGATION_COMPLETED),
        events.stream().map(AgentEvent::type).toList());
    assertEquals(events.getFirst().correlationId(), events.getLast().correlationId());
    assertEquals("project", events.getFirst().projectId());
  }

  @Test
  void unauthorizedAndMissingProjectDoNotExecute() {
    var service =
        new ExternalAgentDelegationService(
            (r, c) -> {
              fail("must not execute");
              return null;
            },
            new CommandCancellationService(),
            new AgentEventFactory(Clock.systemUTC()),
            e -> {},
            Optional.empty());
    assertEquals(
        ExternalAgentResult.Status.REJECTED,
        service.review(run("review this"), "Codex review", null, "").status());
    var noProject = run("Codex にレビューさせて");
    noProject.setRunContext(new AgentRunContext("run", "conversation", root));
    assertEquals(
        ExternalAgentResult.Status.REJECTED,
        service.review(noProject, "review", null, "").status());
  }

  @Test
  void chatGptRequestRejectedByCodexPointsToItsOwnRouteWithoutExecutingCodex() {
    var guidance =
        org.mockito.Mockito.mock(dev.mikoto2000.rei.chatgpt.ChatGptDelegationGuidance.class);
    org.mockito.Mockito.when(guidance.rejectionHint("ChatGPT に資料をレビューして"))
        .thenReturn(
            "\nUse delegateTask with backendId=chatgpt-plan; accountRef=default; modelId=gpt-6.1-sol.");
    var service =
        new ExternalAgentDelegationService(
            (r, c) -> {
              fail("Codex must not execute");
              return null;
            },
            new CommandCancellationService(),
            new AgentEventFactory(Clock.systemUTC()),
            e -> {},
            Optional.empty());
    service.setDelegationGuidance(guidance);
    var result = service.review(run("ChatGPT に資料をレビューして"), "review", null, "");
    assertEquals(ExternalAgentResult.Status.REJECTED, result.status());
    assertTrue(result.summary().contains("explicit user request"));
    assertTrue(result.summary().contains("backendId=chatgpt-plan"));
  }

  @Test
  void acceptsAbsoluteTargetAfterMissingTargetWithoutConsumingBudget() throws Exception {
    Path file = Files.writeString(root.resolve("design.md"), "design").toRealPath();
    List<ExternalAgentRequest> requests = new ArrayList<>();
    var service =
        new ExternalAgentDelegationService(
            (request, cancelled) -> {
              requests.add(request);
              return new ExternalAgentResult(
                  ExternalAgentResult.Status.SUCCESS, "ok", List.of(), List.of(), 1, 0, "");
            },
            new CommandCancellationService(),
            new AgentEventFactory(Clock.systemUTC()),
            e -> {},
            Optional.empty());
    var run = run("Codex にレビューさせて");
    var rejected = service.review(run, "review", root.resolve("missing.md").toString(), "");
    assertEquals(ExternalAgentResult.Status.REJECTED, rejected.status());
    assertEquals("Current project or target does not exist", rejected.summary());
    assertTrue(requests.isEmpty());
    assertTrue(service.review(run, "review", file.toString(), "").success());
    assertEquals(file, requests.getFirst().target());
  }

  @Test
  void failureIsAResultAndRunRemainsActive() {
    List<AgentEvent> events = new ArrayList<>();
    var service =
        new ExternalAgentDelegationService(
            (r, c) -> {
              throw new IllegalStateException("credential should not leak");
            },
            new CommandCancellationService(),
            new AgentEventFactory(Clock.systemUTC()),
            events::add,
            Optional.empty());
    var run = run("Codex にレビューさせて");
    var result = service.review(run, "review", null, "");
    assertEquals(ExternalAgentResult.Status.FAILED, result.status());
    assertDoesNotThrow(run::checkActive);
    assertEquals(AgentEventType.DELEGATION_FAILED, events.getLast().type());
    assertFalse(result.summary().contains("credential"));
  }

  @Test
  void cancellationPublishesExactlyOneTerminalEventAndCancelsRun() {
    List<AgentEvent> events = new ArrayList<>();
    var service =
        new ExternalAgentDelegationService(
            (r, c) ->
                new ExternalAgentResult(
                    ExternalAgentResult.Status.CANCELLED,
                    "cancelled",
                    List.of(),
                    List.of(),
                    2,
                    null,
                    ""),
            new CommandCancellationService(),
            new AgentEventFactory(Clock.systemUTC()),
            events::add,
            Optional.empty());
    var run = run("Codex にレビューさせて");
    assertThrows(
        java.util.concurrent.CancellationException.class,
        () -> service.review(run, "review", null, ""));
    assertEquals(
        List.of(AgentEventType.DELEGATION_STARTED, AgentEventType.DELEGATION_CANCELLED),
        events.stream().map(AgentEvent::type).toList());
    assertTrue(run.isCancelled());
  }

  @Test
  void existingRunCancellationReachesTheExternalExecutor() throws Exception {
    var cancellation = new CommandCancellationService();
    var entered = new java.util.concurrent.CountDownLatch(1);
    List<AgentEvent> events = new java.util.concurrent.CopyOnWriteArrayList<>();
    var service =
        new ExternalAgentDelegationService(
            (r, cancelled) -> {
              entered.countDown();
              long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
              while (!cancelled.getAsBoolean() && System.nanoTime() < deadline)
                java.util.concurrent.locks.LockSupport.parkNanos(1000000);
              assertTrue(cancelled.getAsBoolean());
              return new ExternalAgentResult(
                  ExternalAgentResult.Status.CANCELLED,
                  "cancelled",
                  List.of(),
                  List.of(),
                  1,
                  null,
                  "");
            },
            cancellation,
            new AgentEventFactory(Clock.systemUTC()),
            events::add,
            Optional.empty());
    var run = run("Codex にレビューさせて");
    try (var scope = AgentRunScope.open(run.runContext());
        var pool = java.util.concurrent.Executors.newSingleThreadExecutor()) {
      cancellation.begin(null);
      var future = pool.submit(() -> service.review(run, "review", null, ""));
      assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
      cancellation.cancel();
      var error =
          assertThrows(
              java.util.concurrent.ExecutionException.class,
              () -> future.get(5, java.util.concurrent.TimeUnit.SECONDS));
      assertInstanceOf(java.util.concurrent.CancellationException.class, error.getCause());
      assertEquals(
          List.of(AgentEventType.DELEGATION_STARTED, AgentEventType.DELEGATION_CANCELLED),
          events.stream().map(AgentEvent::type).toList());
      cancellation.clear();
    }
  }

  @Test
  void contextIncludesOnlyRelevantSafeWorkingPaths() throws Exception {
    var ws = new dev.mikoto2000.rei.core.working.WorkingSet();
    ws.recordRead(Files.writeString(root.resolve("design.md"), "private source not copied"));
    ws.recordRead(Files.writeString(root.resolve(".env"), "secret=123"));
    var service =
        new ExternalAgentDelegationService(
            (request, c) -> {
              assertTrue(request.context().contains("design.md"));
              assertFalse(request.context().contains(".env"));
              assertFalse(request.context().contains("private source"));
              assertTrue(request.context().contains("[REDACTED]"));
              return new ExternalAgentResult(
                  ExternalAgentResult.Status.SUCCESS, "ok", List.of(), List.of(), 1, 0, "raw");
            },
            new CommandCancellationService(),
            new AgentEventFactory(Clock.systemUTC()),
            e -> {},
            Optional.of(ws));
    assertEquals(
        "", service.review(run("Codex にレビューさせて"), "review", null, "api_key=secret").rawOutput());
  }
}
