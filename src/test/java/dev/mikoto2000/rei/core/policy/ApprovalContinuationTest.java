package dev.mikoto2000.rei.core.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.calls;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.application.run.RunRegistry;
import dev.mikoto2000.rei.application.run.RunService;
import dev.mikoto2000.rei.application.run.RunStatus;
import dev.mikoto2000.rei.application.session.SessionMetadata;
import dev.mikoto2000.rei.application.session.SessionRepository;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.ChatExecutionResult;
import dev.mikoto2000.rei.core.chat.ChatExecutionService;
import dev.mikoto2000.rei.core.chat.ConversationInputRouter;
import dev.mikoto2000.rei.core.chat.RunContinuation;
import dev.mikoto2000.rei.core.chat.RunWaitingException;
import dev.mikoto2000.rei.core.project.ProjectContext;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.core.stagnation.ProgressEvaluator;
import dev.mikoto2000.rei.core.stagnation.RunExecutionContext;
import dev.mikoto2000.rei.core.stagnation.StagnationChatModel;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.InMemoryAgentEventBus;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import reactor.core.publisher.Flux;

/** 承認継続入力の結果と状態の契約を検証するテスト。 */
@Tag("integration")
class ApprovalContinuationTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path directory;

  /** 検証に使う取得元。 */
  org.sqlite.SQLiteDataSource source;

  /** 検証に使う承認一覧。 */
  ToolApprovalRepository approvals;

  /** 検証に使う保存先。 */
  ApprovalContinuationStore store;

  /** 検証に使うサービス。 */
  ApprovalContinuationService service;

  /** 検証に使う登録台帳。 */
  RunRegistry registry;

  /** 検証に使う実行一覧。 */
  RunService runs;

  /** 検証に使う入力の振り分け。 */
  ConversationInputRouter router;

  /** 検証に使うチャット。 */
  ChatExecutionService chat;

  /** 検証に使うプロジェクト一覧。 */
  ProjectRegistry projects;

  /** 検証に使う所有者。 */
  AgentRunContext owner;

  /** 日時や期限の条件を再現するために使う検証時刻。 */
  Clock clock = Clock.fixed(Instant.parse("2026-10-12T07:00:00Z"), ZoneOffset.UTC);

  /** 検証に使う機能の構成要素一覧。 */
  StaticListableBeanFactory beans;

  /** 各検証を独立した状態で始められるよう、依存先と検証データを準備する。 */
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

  /** 指定された条件を再現するための検証対象や入力情報を作成する。 */
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

  /** {@code execution} の条件を再現するための補助情報を返す。 */
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

  /** {@code pending} の条件を再現するための補助情報を返す。 */
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

  /** {@code pause} の条件を再現するための補助情報を返す。 */
  String pause() {
    var context = execution();
    context.consumeNextLlmCall();
    String id =
        approvals.request("runCommand", pending().calls().getFirst().arguments(), owner).id();
    service.suspendApproval(context, id, List.of(new UserMessage("review")), pending());
    return id;
  }

  /** 承認続行後も所有者の追加入力が同じ実行キューへ届き、完了後は受け付けない。 */
  @Test
  void continuedRunAcceptsOwnedWebGuidanceWithoutResettingSavedBudget() {
    router =
        new ConversationInputRouter(
            Runnable::run,
            (context, prompt, queue) -> {
              throw new AssertionError("Unexpected ordinary runner");
            });
    beans.addBean("router", router);
    service = createService(clock);
    String id = pause();
    service.decide(owner.projectId(), id, true, true);
    var controller = new dev.mikoto2000.rei.web.RunController(runs, router, true);
    when(chat.execute(eq(owner), anyString(), any(), any()))
        .thenAnswer(
            invocation -> {
              assertEquals(RunStatus.RUNNING, registry.get(owner.runId()).status());
              var input =
                  new dev.mikoto2000.rei.web.RunController.InputRequest(
                      owner.projectId(),
                      owner.conversationId(),
                      "Complete all remaining review pages");
              assertEquals(202, controller.input(owner.runId(), input).getStatusCode().value());
              assertTrue(
                  !router.offerIntervention(
                      "wrong-project", owner.conversationId(), owner.runId(), "wrong"));
              assertTrue(
                  !router.offerIntervention(
                      owner.projectId(), "wrong-session", owner.runId(), "wrong"));
              dev.mikoto2000.rei.core.chat.UserInterventionQueue queue = invocation.getArgument(2);
              assertEquals(List.of(input.message()), queue.drain());
              assertEquals(1, store.document(store.find(id).orElseThrow()).budget().llmCalls());
              return ChatExecutionResult.success("done", false);
            });
    service.pump();
    verify(chat, times(1)).execute(eq(owner), anyString(), any(), any());
    assertEquals("COMPLETED", service.state(id).orElseThrow());
    assertTrue(
        !router.offerIntervention(
            owner.projectId(), owner.conversationId(), owner.runId(), "late"));
    assertTrue(router.activeRuns().isEmpty());
  }

  /** ツール利用の権限制御で、保存した情報を、再起動や復元後の処理へ正しく引き継ぐことを確認する。 */
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
    verify(router, times(1)).submitContinuation(eq(owner), anyString(), any(), any());
    assertEquals("RESUMING", store.find(id).orElseThrow().state());
  }

  /** ツール利用の権限制御で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void completedContinuationRetiresAnyGrantNotConsumedByAnAutomaticPolicy() {
    String id = pause();
    service.decide(owner.projectId(), id, true, true);
    when(chat.execute(eq(owner), anyString(), any(), any()))
        .thenReturn(ChatExecutionResult.success("done", false));
    doAnswer(
            invocation -> {
              Consumer<dev.mikoto2000.rei.core.chat.UserInterventionQueue> operation =
                  invocation.getArgument(2);
              Consumer<Runnable> lifecycle = invocation.getArgument(3);
              lifecycle.accept(
                  () -> operation.accept(new dev.mikoto2000.rei.core.chat.UserInterventionQueue()));
              return null;
            })
        .when(router)
        .submitContinuation(eq(owner), anyString(), any(), any());
    service.pump();
    service.pump();
    assertEquals("COMPLETED", service.state(id).orElseThrow());
    assertEquals("CONSUMED", approvals.get(owner.projectId(), id).status());
    service.decide(owner.projectId(), id, true, true);
    verify(router, times(1)).submitContinuation(eq(owner), anyString(), any(), any());
  }

  /** Web判断結果について、継続入力一度だけを受け付けられることを確認する。 */
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
    verify(router, times(1)).submitContinuation(eq(owner), anyString(), any(), any());
  }

  /** ツール利用の権限制御で、指定した条件に対する結果と状態の変化を確認する。 */
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
          /** 検証用に、ツール利用の権限制御の要求を実行し、呼び出し元へ結果を返す。 */
          public ChatResponse call(Prompt p) {
            throw new AssertionError();
          }

          /** {@code stream} の条件を再現するための補助情報を返す。 */
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

  /** {@code callback} の条件を再現するための補助情報を返す。 */
  ToolCallback callback(String name, java.util.function.Function<String, String> call) {
    return new ToolCallback() {
      /** 検証用に、ツール利用の権限制御で参照するツール定義を返す。 */
      public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder().name(name).description(name).inputSchema("{}").build();
      }

      /** 検証用に、ツール利用の権限制御の要求を実行し、呼び出し元へ結果を返す。 */
      public String call(String input) {
        return call.apply(input);
      }
    };
  }

  /** 検証で使用するプロンプトを、指定された条件に合わせて用意する。 */
  Prompt prompt(RunExecutionContext context, ToolCallback... callbacks) {
    return new Prompt(
        List.of(new UserMessage("review")),
        ToolCallingChatOptions.builder()
            .toolCallbacks(List.of(callbacks))
            .toolContext(Map.of(RunExecutionContext.KEY, context))
            .build());
  }

  /** ツール利用の権限制御の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void denialExpiryAndCancellationNeverDispatchTheSavedCommand() {
    String id = pause();
    service.decide(owner.projectId(), id, false, false);
    service.pump();
    assertEquals("DENIED", store.find(id).orElseThrow().state());
    assertEquals(RunStatus.CANCELLED, registry.get(owner.runId()).status());
    verifyNoInteractions(chat);
  }

  /** ツール利用の権限制御の操作が、指定された権限・承認条件に従うことを確認する。 */
  @Test
  void expiredApprovalCannotBeContinued() {
    String id = pause();
    var expired = createService(Clock.offset(clock, Duration.ofMinutes(16)));
    assertThrows(
        IllegalStateException.class, () -> expired.decide(owner.projectId(), id, true, true));
    expired.pump();
    assertEquals("EXPIRED", store.find(id).orElseThrow().state());
    verify(router, never()).submitContinuation(any(), anyString(), any(), any());
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(chat);
  }

  /** ツール利用の権限制御の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
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

  /** ツール利用の権限制御の操作が、指定された権限・承認条件に従うことを確認する。 */
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
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(router, chat);
  }

  /** 待機中受け入れ条件について、再開の後も対象の情報が残ることを確認する。 */
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
    verify(router).submitContinuation(eq(owner), anyString(), any(), any());
    assertEquals(pending().calls(), service.restore(owner).pendingTools().calls());
  }

  /** ツール利用の権限制御で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
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
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(router, chat);
  }

  /** ツール利用の権限制御で、指定した条件に対する結果と状態の変化を確認する。 */
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
          /** 検証用に、ツール利用の権限制御の要求を実行し、呼び出し元へ結果を返す。 */
          public ChatResponse call(Prompt p) {
            throw new AssertionError();
          }

          /** {@code stream} の条件を再現するための補助情報を返す。 */
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

  /** ツール利用の権限制御の操作が、指定された権限・承認条件に従うことを確認する。 */
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
          /** 検証用に、ツール利用の権限制御の要求を実行し、呼び出し元へ結果を返す。 */
          public ChatResponse call(Prompt p) {
            throw new AssertionError();
          }

          /** {@code stream} の条件を再現するための補助情報を返す。 */
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

  /** ツール利用の権限制御の操作が、指定された権限・承認条件に従うことを確認する。 */
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
          /** 検証用に、ツール利用の権限制御の要求を実行し、呼び出し元へ結果を返す。 */
          public ChatResponse call(Prompt p) {
            throw new AssertionError();
          }

          /** {@code stream} の条件を再現するための補助情報を返す。 */
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
