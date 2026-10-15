package dev.mikoto2000.rei.core.policy;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.mikoto2000.rei.application.run.*;
import dev.mikoto2000.rei.application.session.*;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.core.project.*;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.core.stagnation.*;
import dev.mikoto2000.rei.event.*;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import reactor.core.publisher.Flux;

@Tag("integration")
class ApprovalContinuationTest {
  @TempDir Path directory;
  org.sqlite.SQLiteDataSource source;
  ToolApprovalRepository approvals;
  ApprovalContinuationStore store;
  ApprovalContinuationService service;
  RunRegistry registry;
  RunService runs;
  ConversationInputRouter router;
  ChatExecutionService chat;
  ProjectRegistry projects;
  AgentRunContext owner;
  Clock clock = Clock.fixed(Instant.parse("2026-10-12T07:00:00Z"), ZoneOffset.UTC);
  StaticListableBeanFactory beans;

  @BeforeEach
  void setup() {
    source = new org.sqlite.SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + directory.resolve("approvals.db"));
    approvals = new ToolApprovalRepository(source, clock);
    store = new ApprovalContinuationStore(source);
    registry = new RunRegistry(clock, source);
    var bus = new InMemoryAgentEventBus();
    runs =
        new RunService(
            registry,
            bus,
            new AgentEventFactory(clock),
            new CommandCancellationService(),
            id -> false);
    router = mock(ConversationInputRouter.class);
    chat = mock(ChatExecutionService.class);
    projects = mock(ProjectRegistry.class);
    String project = UUID.randomUUID().toString();
    owner = new AgentRunContext("run", "session", directory, project);
    when(projects.resolveById(project))
        .thenReturn(Optional.of(new ProjectContext(project, "project", directory)));
    var sessions = mock(SessionRepository.class);
    when(sessions.findById("session"))
        .thenReturn(
            Optional.of(
                new SessionMetadata("session", project, "task", clock.instant(), clock.instant())));
    beans = new StaticListableBeanFactory();
    beans.addBean("registry", registry);
    beans.addBean("runs", runs);
    beans.addBean("router", router);
    beans.addBean("chat", chat);
    beans.addBean("projects", projects);
    beans.addBean("sessions", sessions);
    service = createService(clock);
    registry.register(owner);
    registry.transition(owner.runId(), RunStatus.RUNNING, null);
  }

  ApprovalContinuationService createService(Clock time) {
    return new ApprovalContinuationService(
        approvals,
        store,
        beans.getBeanProvider(RunRegistry.class),
        beans.getBeanProvider(RunService.class),
        beans.getBeanProvider(ConversationInputRouter.class),
        beans.getBeanProvider(ChatExecutionService.class),
        beans.getBeanProvider(dev.mikoto2000.rei.slack.SlackAgentService.class),
        beans.getBeanProvider(ProjectRegistry.class),
        beans.getBeanProvider(SessionRepository.class),
        source,
        time);
  }

  RunExecutionContext execution() {
    var context =
        new RunExecutionContext(
            owner.runId(),
            new OutputLimitRunBudget(2, 8),
            new ProgressEvaluator(directory),
            new AgentEventFactory(clock),
            event -> {});
    context.setRunContext(owner);
    context.setUserRequest("review");
    context.setContinuation(service);
    var guard =
        new ToolPermissionGuard(
            new ToolPermissionPolicy(
                new ToolPermissionProperties(
                    true, Set.of(ActionCapability.READ), Set.of(), Map.of())),
            new AgentEventFactory(clock),
            event -> {});
    guard.setApprovals(approvals);
    context.setToolPermissionGuard(guard);
    return context;
  }

  RunContinuation.PendingTools pending() {
    return new RunContinuation.PendingTools(
        "run validation",
        List.of(
            new AssistantMessage.ToolCall(
                "write",
                "function",
                "runCommand",
                "{\"request\":{\"command\":\"npm run textlint\"}}")),
        List.of());
  }

  String pause() {
    var context = execution();
    context.consumeNextLlmCall();
    String id =
        approvals.request("runCommand", pending().calls().getFirst().arguments(), owner).id();
    service.suspendApproval(context, id, List.of(new UserMessage("review")), pending());
    return id;
  }

  @Test
  void approveAndContinueIsDurableAndQueuesOnlyOnce() {
    String id = pause();
    assertEquals(RunStatus.WAITING_APPROVAL, registry.get(owner.runId()).status());
    assertEquals(1, store.document(store.find(id).orElseThrow()).budget().llmCalls());
    service.decide(owner.projectId(), id, true, true);
    service.decide(owner.projectId(), id, true, true);
    assertEquals("QUEUED", store.find(id).orElseThrow().state());
    service.pump();
    service.pump();
    verify(router, times(1)).submitOperation(eq(owner), any(Runnable.class), any());
    assertEquals("RESUMING", store.find(id).orElseThrow().state());
  }

  @Test
  void completedContinuationRetiresAnyGrantNotConsumedByAnAutomaticPolicy() {
    String id = pause();
    service.decide(owner.projectId(), id, true, true);
    when(chat.execute(eq(owner), anyString(), any(), any()))
        .thenReturn(ChatExecutionResult.success("done", false));
    doAnswer(
            invocation -> {
              Runnable operation = invocation.getArgument(1);
              Consumer<Runnable> lifecycle = invocation.getArgument(2);
              lifecycle.accept(operation);
              return null;
            })
        .when(router)
        .submitOperation(eq(owner), any(), any());
    service.pump();
    service.pump();
    assertEquals("COMPLETED", service.state(id).orElseThrow());
    assertEquals("CONSUMED", approvals.get(owner.projectId(), id).status());
    service.decide(owner.projectId(), id, true, true);
    verify(router, times(1)).submitOperation(eq(owner), any(), any());
  }

  @Test
  void webDecisionAcceptsContinuationOnceAndReturnsTheSameOwnedRun() throws Exception {
    String id = pause();
    var controller = new dev.mikoto2000.rei.web.ApprovalController(approvals);
    controller.setContinuations(service);
    var mvc =
        org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller)
            .build();
    for (int attempt = 0; attempt < 2; attempt++)
      mvc.perform(
              org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                      "/api/v1/projects/" + owner.projectId() + "/approvals/" + id + "/decision")
                  .contentType("application/json")
                  .content("{\"approved\":true,\"continueRun\":true}"))
          .andExpect(
              org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
          .andExpect(
              org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                      "$.continuationState")
                  .value("QUEUED"))
          .andExpect(
              org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.runId")
                  .value(owner.runId()));
    service.pump();
    verify(router, times(1)).submitOperation(eq(owner), any(), any());
  }

  @Test
  void resumeExecutesExactArgumentsBeforeTheModelAndSkipsTheSuccessfulPrefix() {
    var readCalls = new AtomicInteger();
    var commandCalls = new AtomicInteger();
    var modelCalls = new AtomicInteger();
    var read =
        callback(
            "readMultiFile",
            input -> {
              readCalls.incrementAndGet();
              return "already read";
            });
    String arguments =
        "{\"request\":{\"command\":\"npm run textlint\",\"timeoutSeconds\":30,\"executionMode\":\"auto\"}}";
    var command =
        callback(
            "runCommand",
            input -> {
              assertEquals(arguments, input);
              commandCalls.incrementAndGet();
              return "validation passed";
            });
    var calls =
        List.of(
            new AssistantMessage.ToolCall("read", "function", "readMultiFile", "{}"),
            new AssistantMessage.ToolCall("command", "function", "runCommand", arguments));
    ChatModel model =
        new ChatModel() {
          public ChatResponse call(Prompt p) {
            throw new AssertionError();
          }

          public Flux<ChatResponse> stream(Prompt p) {
            if (modelCalls.incrementAndGet() == 1)
              return Flux.just(
                  new ChatResponse(
                      List.of(
                          new Generation(
                              AssistantMessage.builder()
                                  .content("check")
                                  .toolCalls(calls)
                                  .build()))));
            assertEquals(1, commandCalls.get());
            assertEquals(1, readCalls.get());
            var response =
                p.getInstructions().stream()
                    .filter(m -> m instanceof ToolResponseMessage)
                    .map(m -> (ToolResponseMessage) m)
                    .findFirst()
                    .orElseThrow();
            assertEquals(
                List.of("read", "command"),
                response.getResponses().stream()
                    .map(ToolResponseMessage.ToolResponse::id)
                    .toList());
            return Flux.just(
                new ChatResponse(List.of(new Generation(new AssistantMessage("done")))));
          }
        };
    var controlled = new StagnationChatModel(model);
    var initial = execution();
    initial.consumeNextLlmCall();
    assertThrows(
        RunWaitingException.class,
        () -> controlled.stream(prompt(initial, read, command)).blockLast());
    assertEquals(1, readCalls.get());
    assertEquals(0, commandCalls.get());
    assertEquals(1, modelCalls.get());
    var request = approvals.list(owner.projectId()).getFirst();
    service.decide(owner.projectId(), request.id(), true, true);
    assertTrue(store.claim(request.id()));
    registry.transition(owner.runId(), RunStatus.RUNNING, null);
    var resumed = execution();
    resumed.resume(service.restore(owner));
    assertEquals(
        "done",
        controlled.stream(prompt(resumed, read, command))
            .blockLast()
            .getResult()
            .getOutput()
            .getText());
    assertEquals(2, resumed.budgetSnapshot().llmCalls());
    assertEquals(1, commandCalls.get());
    assertEquals("CONSUMED", approvals.get(owner.projectId(), request.id()).status());
  }

  ToolCallback callback(String name, java.util.function.Function<String, String> call) {
    return new ToolCallback() {
      public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder().name(name).description(name).inputSchema("{}").build();
      }

      public String call(String input) {
        return call.apply(input);
      }
    };
  }

  Prompt prompt(RunExecutionContext context, ToolCallback... callbacks) {
    return new Prompt(
        List.of(new UserMessage("review")),
        ToolCallingChatOptions.builder()
            .toolCallbacks(List.of(callbacks))
            .toolContext(Map.of(RunExecutionContext.KEY, context))
            .build());
  }

  @Test
  void denialExpiryAndCancellationNeverDispatchTheSavedCommand() {
    String id = pause();
    service.decide(owner.projectId(), id, false, false);
    service.pump();
    assertEquals("DENIED", store.find(id).orElseThrow().state());
    assertEquals(RunStatus.CANCELLED, registry.get(owner.runId()).status());
    verifyNoInteractions(chat);
  }

  @Test
  void expiredApprovalCannotBeContinued() {
    String id = pause();
    var expired = createService(Clock.offset(clock, Duration.ofMinutes(16)));
    assertThrows(
        IllegalStateException.class, () -> expired.decide(owner.projectId(), id, true, true));
    expired.pump();
    assertEquals("EXPIRED", store.find(id).orElseThrow().state());
    verify(router, never()).submitOperation(any(), any(), any());
    verifyNoInteractions(chat);
  }

  @Test
  void cancelledRunCannotAcceptOrExecuteContinuation() {
    String id = pause();
    runs.cancel(owner.runId());
    assertThrows(
        IllegalStateException.class, () -> service.decide(owner.projectId(), id, true, true));
    service.pump();
    assertEquals("CANCELLED", store.find(id).orElseThrow().state());
    verifyNoInteractions(router, chat);
  }

  @Test
  void changedProjectAndOtherProjectCannotUseTheSavedApproval() {
    String id = pause();
    assertThrows(IllegalArgumentException.class, () -> service.decide("other", id, true, true));
    when(projects.resolveById(owner.projectId()))
        .thenReturn(
            Optional.of(
                new ProjectContext(owner.projectId(), "moved", directory.resolve("other"))));
    assertThrows(
        IllegalStateException.class, () -> service.decide(owner.projectId(), id, true, true));
    assertEquals("PENDING", approvals.get(owner.projectId(), id).status());
    verifyNoInteractions(router, chat);
  }

  @Test
  void queuedAcceptanceSurvivesRestartWithoutChangingTheInvocation() {
    String id = pause();
    service.decide(owner.projectId(), id, true, true);
    registry.close();
    registry = new RunRegistry(clock, source);
    beans.addBean("registry", registry);
    runs =
        new RunService(
            registry,
            new InMemoryAgentEventBus(),
            new AgentEventFactory(clock),
            new CommandCancellationService(),
            ignored -> false);
    beans.addBean("runs", runs);
    service = createService(clock);
    service.pump();
    verify(router).submitOperation(eq(owner), any(Runnable.class), any());
    assertEquals(pending().calls(), service.restore(owner).pendingTools().calls());
  }

  @Test
  void aDeadClaimIsUnknownAndNeverAutomaticallyRetried() {
    String id = pause();
    service.decide(owner.projectId(), id, true, true);
    assertTrue(store.claim(id));
    org.springframework.jdbc.core.simple.JdbcClient.create(source)
        .sql("UPDATE approval_continuations SET pid=0 WHERE approval=?")
        .param(id)
        .update();
    service.pump();
    assertEquals("UNKNOWN", store.find(id).orElseThrow().state());
    assertThrows(
        IllegalStateException.class, () -> service.decide(owner.projectId(), id, true, true));
    verifyNoInteractions(router, chat);
  }

  @Test
  void currentPolicyCanDenyAnAlreadyApprovedInvocationBeforeItsEffect() {
    String id = pause();
    service.decide(owner.projectId(), id, true, true);
    assertTrue(store.claim(id));
    registry.transition("run", RunStatus.RUNNING, null);
    var resumed = execution();
    resumed.resume(service.restore(owner));
    var denying =
        new ToolPermissionGuard(
            new ToolPermissionPolicy(
                new ToolPermissionProperties(
                    true,
                    Set.of(ActionCapability.READ),
                    Set.of(ActionCapability.EXECUTE),
                    Map.of())),
            new AgentEventFactory(clock),
            event -> {});
    denying.setApprovals(approvals);
    resumed.setToolPermissionGuard(denying);
    var effects = new AtomicInteger();
    var modelCalls = new AtomicInteger();
    var tool =
        callback(
            "runCommand",
            input -> {
              effects.incrementAndGet();
              return "done";
            });
    var model =
        new ChatModel() {
          public ChatResponse call(Prompt p) {
            throw new AssertionError();
          }

          public Flux<ChatResponse> stream(Prompt p) {
            modelCalls.incrementAndGet();
            throw new AssertionError();
          }
        };
    assertThrows(
        ToolPermissionException.class,
        () -> new StagnationChatModel(model).stream(prompt(resumed, tool)).blockLast());
    assertEquals(0, effects.get());
    assertEquals(0, modelCalls.get());
    assertEquals("APPROVED", approvals.get(owner.projectId(), id).status());
  }

  @Test
  void malformedTaskStateSavedByAnOlderVersionIsRepairedAndRequiresFreshApproval() {
    String malformed =
        "{\"goal\":\"review\",\"status\":\"IN_PROGRESS\",\"completed\":[],\"pending\":\"レビュー委譲, 指摘の検証\"}";
    String corrected =
        "{\"goal\":\"review\",\"status\":\"IN_PROGRESS\",\"completed\":[],\"pending\":[\"レビュー委譲\",\"指摘の検証\"]}";
    var oldPending =
        new RunContinuation.PendingTools(
            "save progress",
            List.of(new AssistantMessage.ToolCall("old", "function", "updateTaskState", malformed)),
            List.of());
    var initial = execution();
    initial.consumeNextLlmCall();
    // Older versions admitted malformed arguments before requesting approval. Preserve that exact
    // JSON.
    String oldId = approvals.request("updateTaskState", malformed, owner).id();
    service.suspendApproval(initial, oldId, List.of(new UserMessage("review")), oldPending);
    service.decide(owner.projectId(), oldId, true, true);
    assertTrue(store.claim(oldId));
    registry.transition("run", RunStatus.RUNNING, null);

    var state = new dev.mikoto2000.rei.core.taskstate.TaskState();
    var actual =
        Arrays.stream(
                org.springframework.ai.tool.method.MethodToolCallbackProvider.builder()
                    .toolObjects(new dev.mikoto2000.rei.core.taskstate.TaskStateTools(state))
                    .build()
                    .getToolCallbacks())
            .filter(tool -> tool.getToolDefinition().name().equals("updateTaskState"))
            .findFirst()
            .orElseThrow();
    var effects = new AtomicInteger();
    var modelCalls = new AtomicInteger();
    var tool =
        callback(
            "updateTaskState",
            input -> {
              effects.incrementAndGet();
              assertEquals(corrected, input);
              return actual.call(input);
            });
    var model =
        new ChatModel() {
          public ChatResponse call(Prompt p) {
            throw new AssertionError();
          }

          public Flux<ChatResponse> stream(Prompt p) {
            if (modelCalls.incrementAndGet() == 1) {
              assertEquals(0, effects.get());
              assertEquals("IDLE", state.status());
              assertTrue(state.pending().isEmpty());
              assertEquals("APPROVED", approvals.get(owner.projectId(), oldId).status());
              var feedback =
                  p.getInstructions().stream()
                      .filter(ToolResponseMessage.class::isInstance)
                      .map(ToolResponseMessage.class::cast)
                      .flatMap(message -> message.getResponses().stream())
                      .filter(response -> response.id().equals("old"))
                      .findFirst()
                      .orElseThrow()
                      .responseData();
              assertTrue(feedback.contains("INVALID_TOOL_ARGUMENTS"));
              assertTrue(feedback.contains("pending"));
              assertTrue(feedback.contains("\"executed\":false"));
              return Flux.just(
                  new ChatResponse(
                      List.of(
                          new Generation(
                              AssistantMessage.builder()
                                  .content("correct the array")
                                  .toolCalls(
                                      List.of(
                                          new AssistantMessage.ToolCall(
                                              "corrected",
                                              "function",
                                              "updateTaskState",
                                              corrected)))
                                  .build()))));
            }
            assertEquals(1, effects.get());
            assertEquals("IN_PROGRESS", state.status());
            assertEquals(List.of("レビュー委譲", "指摘の検証"), state.pending());
            return Flux.just(
                new ChatResponse(List.of(new Generation(new AssistantMessage("done")))));
          }
        };
    var controlled = new StagnationChatModel(model);
    var resumed = execution();
    resumed.resume(service.restore(owner));
    assertThrows(
        RunWaitingException.class, () -> controlled.stream(prompt(resumed, tool)).blockLast());
    assertEquals(0, effects.get());
    assertEquals("IDLE", state.status());
    assertEquals(1, modelCalls.get());
    assertEquals(1, resumed.budgetSnapshot().argumentCorrections());
    assertEquals("CONSUMED", approvals.get(owner.projectId(), oldId).status());
    assertEquals("COMPLETED", service.state(oldId).orElseThrow());
    var fresh =
        approvals.list(owner.projectId()).stream()
            .filter(request -> request.status().equals("PENDING"))
            .findFirst()
            .orElseThrow();
    assertNotEquals(oldId, fresh.id());
    assertEquals(corrected, fresh.argumentsPreview());
    assertEquals("WAITING_APPROVAL", service.state(fresh.id()).orElseThrow());
    assertEquals(
        corrected,
        store
            .document(store.find(fresh.id()).orElseThrow())
            .pending()
            .calls()
            .getFirst()
            .arguments());

    service.decide(owner.projectId(), fresh.id(), true, true);
    assertTrue(store.claim(fresh.id()));
    registry.transition("run", RunStatus.RUNNING, null);
    var accepted = execution();
    accepted.resume(service.restore(owner));
    assertEquals(
        "done",
        controlled.stream(prompt(accepted, tool)).blockLast().getResult().getOutput().getText());
    assertEquals(1, effects.get());
    assertEquals("CONSUMED", approvals.get(owner.projectId(), fresh.id()).status());
  }

  @Test
  void aSecondApprovalInTheSameBatchDoesNotSpendAModelCallOrRepeatTheFirstEffect() {
    var effects = new ArrayList<String>();
    var modelCalls = new AtomicInteger();
    var command =
        callback(
            "runCommand",
            input -> {
              effects.add(input);
              return "done";
            });
    String first = "{\"request\":{\"command\":\"first\"}}",
        second = "{\"request\":{\"command\":\"second\"}}";
    var calls =
        List.of(
            new AssistantMessage.ToolCall("first", "function", "runCommand", first),
            new AssistantMessage.ToolCall("second", "function", "runCommand", second));
    ChatModel model =
        new ChatModel() {
          public ChatResponse call(Prompt p) {
            throw new AssertionError();
          }

          public Flux<ChatResponse> stream(Prompt p) {
            return Flux.just(
                new ChatResponse(
                    List.of(
                        new Generation(
                            modelCalls.incrementAndGet() == 1
                                ? AssistantMessage.builder().content("").toolCalls(calls).build()
                                : new AssistantMessage("done")))));
          }
        };
    var controlled = new StagnationChatModel(model);
    var initial = execution();
    initial.consumeNextLlmCall();
    assertThrows(
        RunWaitingException.class, () -> controlled.stream(prompt(initial, command)).blockLast());
    String id = approvals.list(owner.projectId()).getFirst().id();
    service.decide(owner.projectId(), id, true, true);
    assertTrue(store.claim(id));
    registry.transition("run", RunStatus.RUNNING, null);
    var resumed = execution();
    resumed.resume(service.restore(owner));
    assertThrows(
        RunWaitingException.class, () -> controlled.stream(prompt(resumed, command)).blockLast());
    assertEquals(List.of(first), effects);
    assertEquals(1, modelCalls.get());
    assertEquals(1, resumed.budgetSnapshot().llmCalls());
    assertEquals("COMPLETED", service.state(id).orElseThrow());
    String next =
        approvals.list(owner.projectId()).stream()
            .filter(r -> r.status().equals("PENDING"))
            .findFirst()
            .orElseThrow()
            .id();
    service.decide(owner.projectId(), next, true, true);
    assertTrue(store.claim(next));
    registry.transition("run", RunStatus.RUNNING, null);
    var finalRun = execution();
    finalRun.resume(service.restore(owner));
    assertEquals(
        "done",
        controlled.stream(prompt(finalRun, command)).blockLast().getResult().getOutput().getText());
    assertEquals(List.of(first, second), effects);
    assertEquals(2, modelCalls.get());
    assertEquals(2, finalRun.budgetSnapshot().llmCalls());
  }
}
