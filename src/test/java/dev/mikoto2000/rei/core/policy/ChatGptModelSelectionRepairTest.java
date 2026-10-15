package dev.mikoto2000.rei.core.policy;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.mikoto2000.rei.chatgpt.*;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.core.stagnation.*;
import dev.mikoto2000.rei.event.*;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;
import dev.mikoto2000.rei.subagent.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import reactor.core.publisher.Flux;

class ChatGptModelSelectionRepairTest {
  private static final PlanDelegationProperties AUTOMATIC =
      new PlanDelegationProperties(true, Set.of("default"), Set.of("gpt-6.1-sol"));
  private static final String WRONG_MODEL = "private-guessed-alias";

  private static String request(String backend, String account, String model) {
    return "{\"agent\":\"reviewer\",\"task\":\"review\",\"backendId\":\""
        + backend
        + "\",\"accountRef\":\""
        + account
        + "\",\"modelId\":\""
        + model
        + "\",\"sourceRefs\":[\"docs/a.md\"],\"expectedOutput\":\"{\\\"unverified\\\":[]}\"}";
  }

  private static String withContext(String input) {
    return input.substring(0, input.length() - 1) + ",\"context\":\"private-context\"}";
  }

  private static ChatResponse response(String id, String name, String input) {
    return new ChatResponse(
        List.of(
            new Generation(
                AssistantMessage.builder()
                    .toolCalls(List.of(new AssistantMessage.ToolCall(id, "function", name, input)))
                    .build())));
  }

  private static final class Fixture {
    final AgentRunContext owner;
    final ToolApprovalRepository approvals = mock(ToolApprovalRepository.class);
    final AtomicInteger effects = new AtomicInteger();
    final List<AgentEvent> events = new CopyOnWriteArrayList<>();
    final ToolPermissionGuard guard;
    final OutputLimitRunBudget budget;
    final RunExecutionContext context;

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

    Fixture(PlanDelegationProperties automatic) {
      this(automatic, Set.of(), AgentRunContext.Mode.EXCLUSIVE, new OutputLimitRunBudget(2, 20));
    }

    ToolCallback tool(String name) {
      return new ToolCallback() {
        public ToolDefinition getToolDefinition() {
          return ToolDefinition.builder()
              .name(name)
              .description("delegate")
              .inputSchema("{}")
              .build();
        }

        public String call(String input) {
          guard.check(name, input, owner);
          effects.incrementAndGet();
          throw new AssertionError("No child should execute without its own exact approval");
        }
      };
    }

    Prompt prompt(String name) {
      return new Prompt(
          List.of(new UserMessage("ChatGPT にレビューして")),
          ToolCallingChatOptions.builder()
              .toolCallbacks(List.of(tool(name)))
              .toolContext(Map.of(RunExecutionContext.KEY, context))
              .build());
    }

    ToolExecutionResult run(String id, String name, String input) {
      return new ToolLoopSupport()
          .executeResumable(prompt(name), response(id, name, input), context);
    }
  }

  private static String feedback(ToolExecutionResult result) {
    return result.conversationHistory().stream()
        .filter(ToolResponseMessage.class::isInstance)
        .map(ToolResponseMessage.class::cast)
        .flatMap(message -> message.getResponses().stream())
        .findFirst()
        .orElseThrow()
        .responseData();
  }

  @Test
  void contextAndModelProblemsAreReturnedTogetherAndCorrectedArgumentsStillRequireApproval() {
    var fixture = new Fixture(AUTOMATIC);
    var calls = new AtomicInteger();
    String malformed = withContext(request("chatgpt-plan", "default", WRONG_MODEL));
    String corrected = request("chatgpt-plan", "default", "gpt-6.1-sol");
    var model =
        new ChatModel() {
          public ChatResponse call(Prompt prompt) {
            throw new AssertionError();
          }

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
    verifyNoInteractions(fixture.approvals, resumed.approvals);
  }

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
    verifyNoInteractions(fixture.approvals);
  }

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
    verifyNoInteractions(fixture.approvals);
  }

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
      verifyNoInteractions(fixture.approvals);
    }
  }

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
      verifyNoInteractions(fixture.approvals);
    }
  }

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
      verifyNoInteractions(fixture.approvals);
    }
  }

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
      verifyNoInteractions(fixture.approvals);
    }
  }

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
