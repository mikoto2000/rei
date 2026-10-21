package dev.mikoto2000.rei.core.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.calls;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.chatgpt.ChatGptException;
import dev.mikoto2000.rei.chatgpt.PlanDelegationProperties;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.ToolLoopSupport;
import dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException;
import dev.mikoto2000.rei.core.stagnation.ProgressEvaluator;
import dev.mikoto2000.rei.core.stagnation.RunExecutionContext;
import dev.mikoto2000.rei.core.stagnation.StagnationChatModel;
import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.ToolFailedPayload;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;
import dev.mikoto2000.rei.subagent.DelegationToolPermissionResolver;
import dev.mikoto2000.rei.subagent.SubAgentDefinition;
import dev.mikoto2000.rei.subagent.SubAgentRegistry;
import dev.mikoto2000.rei.subagent.SubAgentToolPolicy;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import reactor.core.publisher.Flux;

/** チャットGPTモデル選択内容修復の結果と状態の契約を検証するテスト。 */
class ChatGptModelSelectionRepairTest {
  /** 検証に使う自動。 */
  private static final PlanDelegationProperties AUTOMATIC =
      new PlanDelegationProperties(true, Set.of("default"), Set.of("gpt-6.1-sol"));

  /** 検証に使うWRONGモデル。 */
  private static final String WRONG_MODEL = "private-guessed-alias";

  /** 検証で使用する要求を、指定された条件に合わせて用意する。 */
  private static String request(String backend, String account, String model) {
    return "{\"agent\":\"reviewer\",\"task\":\"review\",\"backendId\":\""
        + backend
        + "\",\"accountRef\":\""
        + account
        + "\",\"modelId\":\""
        + model
        + "\",\"sourceRefs\":[\"docs/a.md\"],\"expectedOutput\":\"{\\\"unverified\\\":[]}\"}";
  }

  /** 検証用に、指定された文脈を反映した情報を作成する。 */
  private static String withContext(String input) {
    return input.substring(0, input.length() - 1) + ",\"context\":\"private-context\"}";
  }

  /** 検証で使用する応答を、指定された条件に合わせて用意する。 */
  private static ChatResponse response(String id, String name, String input) {
    return new ChatResponse(
        List.of(
            new Generation(
                AssistantMessage.builder()
                    .toolCalls(List.of(new AssistantMessage.ToolCall(id, "function", name, input)))
                    .build())));
  }

  /** 検証条件を再現するための検証条件。 */
  private static final class Fixture {
    /** 検証に使う所有者。 */
    final AgentRunContext owner;

    /** 検証に使う承認一覧。 */
    final ToolApprovalRepository approvals = mock(ToolApprovalRepository.class);

    /** 検証に使う影響。 */
    final AtomicInteger effects = new AtomicInteger();

    /** 通知された出来事と順序を検証するための記録。 */
    final List<AgentEvent> events = new CopyOnWriteArrayList<>();

    /** 検証に使う利用条件の確認。 */
    final ToolPermissionGuard guard;

    /** 検証に使う予算。 */
    final OutputLimitRunBudget budget;

    /** 検証に使う文脈。 */
    final RunExecutionContext context;

    /** 検証条件に必要な値を受け取り、Fixtureを用意する。 */
    Fixture(
        PlanDelegationProperties automatic,
        Set<ActionCapability> denied,
        AgentRunContext.Mode mode,
        OutputLimitRunBudget budget) {
      this(
          automatic,
          denied,
          new AgentRunContext(
              "run", "session", Path.of("."), "project", AgentRunContext.RequestSource.WEB, mode),
          budget);
    }

    /** 検証条件に必要な値を受け取り、Fixtureを用意する。 */
    Fixture(
        PlanDelegationProperties automatic,
        Set<ActionCapability> denied,
        AgentRunContext owner,
        OutputLimitRunBudget budget) {
      this.owner = owner;
      var registry = mock(SubAgentRegistry.class);
      when(registry.findById("reviewer"))
          .thenReturn(
              Optional.of(
                  new SubAgentDefinition(
                      "reviewer",
                      "reviewer",
                      "review",
                      "review",
                      List.of(),
                      "local-model",
                      4,
                      Duration.ofSeconds(30),
                      Path.of("reviewer.yaml"))));
      var beans = new StaticListableBeanFactory();
      beans.addBean("registry", registry);
      beans.addBean("tools", new SubAgentToolPolicy(Set.of()));
      var resolver =
          new DelegationToolPermissionResolver(
              beans.getBeanProvider(SubAgentRegistry.class),
              beans.getBeanProvider(SubAgentToolPolicy.class));
      resolver.setAutomatic(automatic);
      var policy =
          new ToolPermissionPolicy(new ToolPermissionProperties(true, Set.of(), denied, Map.of()));
      policy.setDelegationResolver(resolver);
      var factory = new AgentEventFactory(Clock.systemUTC());
      guard = new ToolPermissionGuard(policy, factory, events::add);
      guard.setApprovals(approvals);
      this.budget = budget;
      context =
          new RunExecutionContext(
              "run", budget, new ProgressEvaluator(Path.of("."), null), factory, events::add);
      context.setRunContext(owner);
      context.setToolPermissionGuard(guard);
      when(approvals.request(anyString(), anyString(), eq(owner)))
          .thenAnswer(
              invocation ->
                  new ToolApprovalRepository.Request(
                      "fresh-approval",
                      "project",
                      "session",
                      "run",
                      invocation.getArgument(0),
                      invocation.getArgument(1),
                      "PENDING",
                      Instant.now().plusSeconds(60)));
    }

    /** 検証条件に必要な値を受け取り、Fixtureを用意する。 */
    Fixture(PlanDelegationProperties automatic) {
      this(automatic, Set.of(), AgentRunContext.Mode.EXCLUSIVE, new OutputLimitRunBudget(2, 20));
    }

    /** {@code tool} の条件を再現するための補助情報を返す。 */
    ToolCallback tool(String name) {
      return new ToolCallback() {
        /** 検証用に、ツール利用の権限制御で参照するツール定義を返す。 */
        public ToolDefinition getToolDefinition() {
          return ToolDefinition.builder()
              .name(name)
              .description("delegate")
              .inputSchema("{}")
              .build();
        }

        /** 検証用に、ツール利用の権限制御の要求を実行し、呼び出し元へ結果を返す。 */
        public String call(String input) {
          guard.check(name, input, owner);
          effects.incrementAndGet();
          throw new AssertionError("No child should execute without its own exact approval");
        }
      };
    }

    /** 検証で使用するプロンプトを、指定された条件に合わせて用意する。 */
    Prompt prompt(String name) {
      return new Prompt(
          List.of(new UserMessage("ChatGPT にレビューして")),
          ToolCallingChatOptions.builder()
              .toolCallbacks(List.of(tool(name)))
              .toolContext(Map.of(RunExecutionContext.KEY, context))
              .build());
    }

    /** 検証用に、ツール利用の権限制御の操作を実行する。 */
    ToolExecutionResult run(String id, String name, String input) {
      return new ToolLoopSupport()
          .executeResumable(prompt(name), response(id, name, input), context);
    }
  }

  /** {@code feedback} の条件を再現するための補助情報を返す。 */
  private static String feedback(ToolExecutionResult result) {
    return result.conversationHistory().stream()
        .filter(ToolResponseMessage.class::isInstance)
        .map(ToolResponseMessage.class::cast)
        .flatMap(message -> message.getResponses().stream())
        .findFirst()
        .orElseThrow()
        .responseData();
  }

  /** ツール利用の権限制御の操作が、指定された権限・承認条件に従うことを確認する。 */
  @Test
  void contextAndModelProblemsAreReturnedTogetherAndCorrectedArgumentsStillRequireApproval() {
    var fixture = new Fixture(AUTOMATIC);
    var calls = new AtomicInteger();
    String malformed = withContext(request("chatgpt-plan", "default", WRONG_MODEL));
    String corrected = request("chatgpt-plan", "default", "gpt-6.1-sol");
    var model =
        new ChatModel() {
          /** 検証用に、ツール利用の権限制御の要求を実行し、呼び出し元へ結果を返す。 */
          public ChatResponse call(Prompt prompt) {
            throw new AssertionError();
          }

          /** {@code stream} の条件を再現するための補助情報を返す。 */
          public Flux<ChatResponse> stream(Prompt prompt) {
            if (calls.incrementAndGet() == 1)
              return Flux.just(response("bad", "delegateTask", malformed));
            assertEquals(2, calls.get());
            var feedback =
                prompt.getInstructions().stream()
                    .filter(ToolResponseMessage.class::isInstance)
                    .map(ToolResponseMessage.class::cast)
                    .flatMap(message -> message.getResponses().stream())
                    .findFirst()
                    .orElseThrow()
                    .responseData();
            assertTrue(feedback.contains("request.context"));
            assertTrue(feedback.contains("request.modelId"));
            assertTrue(feedback.contains("gpt-6.1-sol"));
            assertTrue(feedback.contains("human explicitly requested a different model"));
            assertTrue(feedback.contains("INVALID_TOOL_ARGUMENTS"));
            assertTrue(feedback.contains("\"executed\":false"));
            assertFalse(feedback.contains(WRONG_MODEL));
            assertFalse(feedback.contains("private-context"));
            return Flux.just(response("corrected", "delegateTask", corrected));
          }
        };
    assertThrows(
        ToolPermissionException.class,
        () -> new StagnationChatModel(model).call(fixture.prompt("delegateTask")));
    assertEquals(2, calls.get());
    assertEquals(1, fixture.budget.snapshot().argumentCorrections());
    assertEquals(0, fixture.effects.get());
    verify(fixture.approvals, times(1)).request("delegateTask", corrected, fixture.owner);
    verify(fixture.approvals, never()).request("delegateTask", malformed, fixture.owner);
    assertEquals(Set.of("gpt-6.1-sol"), AUTOMATIC.allowedModels());
  }

  /** 繰り返された未対応のモデル一覧について、既存の修復上限を利用することを確認する。 */
  @Test
  void repeatedUnsupportedModelsUseTheExistingRepairLimitAcrossBudgetRestore() {
    var fixture =
        new Fixture(
            AUTOMATIC,
            Set.of(),
            AgentRunContext.Mode.EXCLUSIVE,
            new OutputLimitRunBudget(2, 20, null, 0, 2));
    String input = request("chatgpt-plan", "default", WRONG_MODEL);
    for (int attempt = 0; attempt < 2; attempt++) {
      String result = feedback(fixture.run("bad-" + attempt, "delegateTask", input));
      assertTrue(result.contains("request.modelId"));
      assertTrue(result.contains("gpt-6.1-sol"));
    }
    var restored = new OutputLimitRunBudget(2, 20, null, 0, 2);
    restored.restore(fixture.budget.snapshot());
    var resumed = new Fixture(AUTOMATIC, Set.of(), AgentRunContext.Mode.EXCLUSIVE, restored);
    var stopped =
        assertThrows(
            ExecutionStoppedException.class, () -> resumed.run("third", "delegateTask", input));
    assertEquals(ExecutionStoppedException.Reason.TOOL_ARGUMENT_REPAIR_EXHAUSTED, stopped.reason());
    assertEquals(2, restored.snapshot().argumentCorrections());
    assertEquals(0, fixture.effects.get());
    assertEquals(0, resumed.effects.get());
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(fixture.approvals, resumed.approvals);
  }

  /** ツール利用の権限制御で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void batchListsAllModelSelectionProblemsWithTheirRequestPaths() {
    var fixture = new Fixture(AUTOMATIC);
    String first =
        withContext(request("chatgpt-plan", "default", WRONG_MODEL))
            .replaceFirst("\\{", "{\"id\":\"first\",");
    String second =
        request("chatgpt-plan", "default", "other-private-alias")
            .replaceFirst("\\{", "{\"id\":\"second\",");
    String input = "{\"requests\":[" + first + "," + second + "]}";
    String result = feedback(fixture.run("batch", "delegateTasks", input));
    assertTrue(result.contains("requests[0].context"));
    assertTrue(result.contains("requests[0].modelId"));
    assertTrue(result.contains("requests[1].modelId"));
    assertTrue(result.contains("gpt-6.1-sol"));
    assertFalse(result.contains(WRONG_MODEL));
    assertFalse(result.contains("other-private-alias"));
    assertEquals(1, fixture.budget.snapshot().argumentCorrections());
    assertEquals(0, fixture.effects.get());
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(fixture.approvals);
  }

  /** ツール利用の権限制御の操作が、指定された権限・承認条件に従うことを確認する。 */
  @Test
  void modelSelectionErrorsKeepTheirTypedMarkerThroughThePermissionPolicy() {
    var fixture = new Fixture(AUTOMATIC);
    var error =
        assertThrows(
            InvalidToolArgumentsException.class,
            () ->
                fixture.guard.validateArguments(
                    "delegateTask",
                    withContext(request("chatgpt-plan", "default", WRONG_MODEL)),
                    fixture.owner));
    assertEquals(InvalidToolArgumentsException.Kind.MODEL_SELECTION, error.kind());
    assertTrue(error.getMessage().contains("request.context"));
    assertTrue(error.getMessage().contains("request.modelId"));
    assertEquals(0, fixture.budget.snapshot().argumentCorrections());
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(fixture.approvals);
  }

  /** ツール利用の権限制御で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void administratorAndOwnerDenialsWinOverModelSelectionRepair() {
    for (var mode :
        List.of(
            AgentRunContext.Mode.EXCLUSIVE,
            AgentRunContext.Mode.READ_ONLY,
            AgentRunContext.Mode.CONVERSATION)) {
      var denied =
          mode == AgentRunContext.Mode.EXCLUSIVE
              ? Set.of(ActionCapability.NETWORK_WRITE)
              : Set.<ActionCapability>of();
      var fixture = new Fixture(AUTOMATIC, denied, mode, new OutputLimitRunBudget(2, 20));
      var error =
          assertThrows(
              ToolPermissionException.class,
              () ->
                  fixture.run(
                      "denied", "delegateTask", request("chatgpt-plan", "default", WRONG_MODEL)));
      assertEquals(PermissionDecision.DENY, error.decision());
      assertEquals(0, fixture.budget.snapshot().argumentCorrections());
      assertEquals(0, fixture.effects.get());
      // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
      verifyNoInteractions(fixture.approvals);
    }
  }

  /** ツール利用の権限制御で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void mcpAndChildOwnersCannotTurnTheirDelegationDenialIntoModelRepair() {
    var owners =
        List.of(
            new AgentRunContext(
                "run",
                "session",
                Path.of("."),
                "project",
                AgentRunContext.RequestSource.MCP,
                AgentRunContext.Mode.EXCLUSIVE),
            new AgentRunContext(
                "run",
                "subagent:child",
                Path.of("."),
                "project",
                AgentRunContext.RequestSource.WEB,
                AgentRunContext.Mode.EXCLUSIVE));
    for (var owner : owners) {
      var fixture = new Fixture(AUTOMATIC, Set.of(), owner, new OutputLimitRunBudget(2, 20));
      var error =
          assertThrows(
              ToolPermissionException.class,
              () ->
                  fixture.run(
                      "denied", "delegateTask", request("chatgpt-plan", "default", WRONG_MODEL)));
      assertEquals(PermissionDecision.DENY, error.decision());
      assertEquals(0, fixture.budget.snapshot().argumentCorrections());
      assertEquals(0, fixture.effects.get());
      // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
      verifyNoInteractions(fixture.approvals);
    }
  }

  /** ツール利用の権限制御で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void genuineAccountDenialInABatchWinsOverAnotherRequestsModelSelectionError() {
    for (boolean refusedFirst : List.of(true, false)) {
      var fixture = new Fixture(AUTOMATIC);
      String refused =
          request("chatgpt-plan", "private-forbidden-account", "gpt-6.1-sol")
              .replaceFirst("\\{", "{\"id\":\"refused\",");
      String wrongModel =
          request("chatgpt-plan", "default", WRONG_MODEL)
              .replaceFirst("\\{", "{\"id\":\"wrong-model\",");
      String input =
          "{\"requests\":["
              + (refusedFirst ? refused + "," + wrongModel : wrongModel + "," + refused)
              + "]}";
      var error =
          assertThrows(
              ChatGptException.class, () -> fixture.run("refused-batch", "delegateTasks", input));
      assertEquals(ChatGptException.PermissionReason.ACCOUNT_NOT_ALLOWED, error.permissionReason());
      assertEquals(0, fixture.budget.snapshot().argumentCorrections());
      assertEquals(0, fixture.effects.get());
      // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
      verifyNoInteractions(fixture.approvals);
    }
  }

  /** ツール利用の権限制御で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void missingAndNullAccountsRemainPureArgumentErrorsWithoutModelSelectionOrNullDereferences() {
    for (String input :
        List.of(
            request("chatgpt-plan", "default", WRONG_MODEL)
                .replace("\"accountRef\":\"default\",", ""),
            request("chatgpt-plan", "default", WRONG_MODEL)
                .replace("\"accountRef\":\"default\"", "\"accountRef\":null"))) {
      var fixture = new Fixture(AUTOMATIC);
      var error =
          assertThrows(
              InvalidToolArgumentsException.class,
              () -> fixture.guard.validateArguments("delegateTask", input, fixture.owner));
      assertEquals(InvalidToolArgumentsException.Kind.VALIDATION, error.kind());
      assertTrue(error.getMessage().contains("ACCOUNT_REQUIRED"));
      String result = feedback(fixture.run("missing-account", "delegateTask", input));
      assertTrue(result.contains("ACCOUNT_REQUIRED"));
      assertEquals(1, fixture.budget.snapshot().argumentCorrections());
      assertEquals(0, fixture.effects.get());
      // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
      verifyNoInteractions(fixture.approvals);
    }
  }

  /** ツール利用の権限制御で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void unavailableOrOtherBackendRoutesRemainTerminalPolicyRefusals() {
    assertTerminal(
        new PlanDelegationProperties(false, Set.of("default"), Set.of("gpt-6.1-sol")),
        "chatgpt-plan",
        "default",
        ChatGptException.PermissionReason.AUTO_DELEGATION_DISABLED);
    assertTerminal(
        AUTOMATIC,
        "chatgpt-plan",
        "private-forbidden-account",
        ChatGptException.PermissionReason.ACCOUNT_NOT_ALLOWED);
    assertTerminal(
        new PlanDelegationProperties(true, Set.of("default"), Set.of()),
        "chatgpt-plan",
        "default",
        ChatGptException.PermissionReason.MODEL_NOT_ALLOWED);
    assertTerminal(
        AUTOMATIC,
        "codex-app-server",
        "default",
        ChatGptException.PermissionReason.MODEL_NOT_ALLOWED);
  }

  /** 複数の検証で共通する期待結果を確認し、要件に合わない状態を検出する。 */
  private void assertTerminal(
      PlanDelegationProperties automatic,
      String backend,
      String account,
      ChatGptException.PermissionReason expected) {
    var fixture = new Fixture(automatic);
    var error =
        assertThrows(
            ChatGptException.class,
            () -> fixture.run("refused", "delegateTask", request(backend, account, WRONG_MODEL)));
    assertEquals(expected, error.permissionReason());
    assertEquals(0, fixture.budget.snapshot().argumentCorrections());
    assertEquals(0, fixture.effects.get());
    verifyNoInteractions(fixture.approvals);
    assertTrue(
        fixture.events.stream()
            .anyMatch(
                event ->
                    event.payload() instanceof ToolFailedPayload failed
                        && "chatgpt_permission_denied".equals(failed.error().code())));
  }
}
