package dev.mikoto2000.rei.subagent;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.mikoto2000.rei.application.run.*;
import dev.mikoto2000.rei.chatgpt.*;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.core.policy.*;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.event.*;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget.LlmCallReservation;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class ChatGptSubAgentBackendTest {
  @TempDir Path root;
  PlanClient client;
  ToolPermissionGuard permissions;
  SubAgentRegistry registry;
  RunRegistry runs;
  AgentEventPublisher publisher;
  CommandCancellationService cancellation;
  ChatGptSubAgentBackend backend;
  AgentRunContext owner;
  static final String ENVELOPE =
      "{\"status\":\"SUCCESS\",\"summary\":\"reviewed\",\"warnings\":[],\"result\":{\"decision\":\"accept\",\"conclusion\":\"ok\",\"findings\":[],\"proposals\":[],\"unverified\":[],\"references\":[]}}";
  static final String VALID =
      "{\"subagent\":"
          + ENVELOPE
          + ",\"conclusion\":\"reviewed\",\"findings\":[],\"proposals\":[],\"unverified\":[],\"scope\":[],\"references\":[]}";

  @BeforeEach
  void setup() throws Exception {
    Files.writeString(
        root.resolve("review.yaml"),
        "id: design-review\n"
            + "name: Design\n"
            + "description: Review\n"
            + "systemPrompt: Review the supplied task\n"
            + "tools: []\n"
            + "maxSteps: 1\n"
            + "timeout: 120s\n");
    registry =
        new SubAgentRegistry(
            root, new SubAgentDefinitionLoader(new SubAgentToolPolicy(Set.of()), m -> true));
    assertTrue(registry.reload().isEmpty());
    client = mock(PlanClient.class);
    permissions = mock(ToolPermissionGuard.class);
    runs = new RunRegistry(Clock.systemUTC());
    cancellation = new CommandCancellationService();
    publisher = mock(AgentEventPublisher.class);
    backend =
        new ChatGptSubAgentBackend(
            client,
            new PlanProperties(true, root, Set.of("design-review")),
            registry,
            permissions,
            cancellation,
            new AgentEventFactory(Clock.systemUTC()),
            publisher,
            Clock.systemUTC(),
            runs);
    owner = new AgentRunContext("parent", "session", root, "project");
    when(client.infer(
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            any(),
            anyInt(),
            anyInt(),
            any(),
            any()))
        .thenAnswer(
            call -> {
              if (call.getArgument(8) != null) ((Runnable) call.getArgument(8)).run();
              return new PlanSseAssembler.Result(
                  PlanSseAssembler.State.COMPLETED, VALID, 17, "resp", null);
            });
  }

  ChatGptSubAgentBackend.Request request(List<String> sources, String expected) {
    return new ChatGptSubAgentBackend.Request(
        "design-review", "chatgpt-plan", "model", "default", "Review", sources, expected);
  }

  @Test
  void validManualDelegationUsesExistingEnvelopeAndRun() {
    var receipt = backend.run(request(List.of(), "{\"decision\":\"accept\"}"), owner, true, null);
    assertEquals(ChatGptSubAgentBackend.State.COMPLETED, receipt.state());
    assertEquals(SubAgentResult.Status.COMPLETED, receipt.result().status());
    assertEquals(RunStatus.COMPLETED, runs.get(receipt.requestId()).status());
    assertEquals("project", receipt.projectId());
    assertEquals("session", receipt.sessionId());
    assertEquals(0, receipt.result().repairAttempts());
    verify(permissions).check(eq("requestChatGptPlanSubAgent"), anyString(), eq(owner));
  }

  @Test
  void terminalPersistenceFailureDoesNotLeakAdmissionPermit() {
    doAnswer(
            call -> {
              var event = (AgentEvent) call.getArgument(0);
              if (event.payload() instanceof SubAgentLifecyclePayload payload
                  && payload.status().equals("COMPLETED"))
                throw new IllegalStateException("storage unavailable");
              return null;
            })
        .when(publisher)
        .publish(any());
    assertThrows(
        IllegalStateException.class,
        () -> backend.run(request(List.of(), "{\"decision\":\"accept\"}"), owner, true, null));
    reset(publisher);
    assertEquals(
        ChatGptSubAgentBackend.State.COMPLETED,
        backend.run(request(List.of(), "{\"decision\":\"accept\"}"), owner, true, null).state());
  }

  @Test
  void parentRunAndBudgetAreRetained() {
    runs.register(owner);
    runs.transition("parent", RunStatus.RUNNING, null);
    var budget = mock(LlmCallReservation.class);
    when(budget.tryReserve()).thenReturn(true);
    var receipt =
        backend.run(request(List.of(), "{\"decision\":\"accept\"}"), owner, false, budget);
    assertEquals("parent", receipt.parentRunId());
    assertEquals("parent", runs.get(receipt.requestId()).childOrigin().parentRunId());
    verify(budget, times(1)).tryReserve();
    verify(budget).recordTotalTokens(17);
  }

  @Test
  void budgetExhaustionPreventsInference() {
    runs.register(owner);
    runs.transition("parent", RunStatus.RUNNING, null);
    var budget = mock(LlmCallReservation.class);
    when(budget.tryReserve()).thenReturn(false);
    var receipt =
        backend.run(request(List.of(), "{\"decision\":\"accept\"}"), owner, false, budget);
    assertEquals(ChatGptSubAgentBackend.State.FAILED, receipt.state());
    verifyNoInteractions(client);
  }

  @Test
  void recursiveAndReadOnlyOwnersAreRejected() {
    for (var bad :
        List.of(
            new AgentRunContext("p", "subagent:child", root, "project"),
            new AgentRunContext(
                "p",
                "s",
                root,
                "project",
                AgentRunContext.RequestSource.SHELL,
                AgentRunContext.Mode.READ_ONLY)))
      assertThrows(
          ChatGptException.class,
          () ->
              backend.run(
                  request(List.of(), "{\"decision\":\"accept\"}"),
                  bad,
                  false,
                  mock(LlmCallReservation.class)));
    verifyNoInteractions(client);
  }

  @Test
  void unknownRoleAndUnapprovedBackendAreRejected() {
    var bad =
        new ChatGptSubAgentBackend.Request(
            "unknown", "chatgpt-plan", "model", "default", "Review", List.of(), "{}");
    assertThrows(ChatGptException.class, () -> backend.run(bad, owner, true, null));
    var other =
        new ChatGptSubAgentBackend.Request(
            "design-review", "other", "model", "default", "Review", List.of(), "{}");
    var finalBad = other;
    assertThrows(ChatGptException.class, () -> backend.run(finalBad, owner, true, null));
    verifyNoInteractions(client);
  }

  @Test
  void malformedModelIdIsRejectedBeforeEventsOrTransport() {
    for (String model : List.of("../other", "model with spaces", "x".repeat(129)))
      assertThrows(
          ChatGptException.class,
          () ->
              backend.run(
                  new ChatGptSubAgentBackend.Request(
                      "design-review",
                      "chatgpt-plan",
                      model,
                      "default",
                      "Review",
                      List.of(),
                      "{\"decision\":\"accept\"}"),
                  owner,
                  true,
                  null));
    verifyNoInteractions(client, publisher);
  }

  @Test
  void requiredResultConditionIsChecked() {
    var receipt = backend.run(request(List.of(), "{\"decision\":\"reject\"}"), owner, true, null);
    assertEquals(ChatGptException.Code.VALIDATION_FAILED, receipt.error());
    assertEquals(RunStatus.FAILED, runs.get(receipt.requestId()).status());
  }

  @Test
  void invalidJsonAndRequiredFieldsNeverRetry() {
    for (String raw :
        List.of("not-json", "{\"status\":\"SUCCESS\"}", VALID.replace("\"scope\":[],", ""))) {
      when(client.infer(
              anyString(),
              anyString(),
              anyString(),
              anyString(),
              any(),
              anyInt(),
              anyInt(),
              any(),
              any()))
          .thenReturn(
              new PlanSseAssembler.Result(PlanSseAssembler.State.COMPLETED, raw, 17, "resp", null));
      assertEquals(
          ChatGptException.Code.VALIDATION_FAILED,
          backend.run(request(List.of(), "{\"decision\":\"accept\"}"), owner, true, null).error());
    }
    verify(client, times(3))
        .infer(
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            any(),
            anyInt(),
            anyInt(),
            any(),
            any());
  }

  @Test
  void crossProjectFilesAndCredentialFilesNeverLeaveProcess() throws Exception {
    Files.writeString(root.resolve("secret.txt"), "refresh_token=secret-value");
    for (String path :
        List.of("../foreign.txt", root.resolve("review.yaml").toString(), "secret.txt", ".env")) {
      var receipt =
          backend.run(request(List.of(path), "{\"decision\":\"accept\"}"), owner, true, null);
      assertEquals(ChatGptSubAgentBackend.State.FAILED, receipt.state());
    }
    verifyNoInteractions(client);
  }

  @Test
  void denialPreventsExternalTransmission() {
    doThrow(new ToolPermissionException("requestChatGptPlanSubAgent", PermissionDecision.DENY))
        .when(permissions)
        .check(eq("requestChatGptPlanSubAgent"), anyString(), eq(owner));
    assertEquals(
        ChatGptSubAgentBackend.State.FAILED,
        backend.run(request(List.of(), "{\"decision\":\"accept\"}"), owner, true, null).state());
    verifyNoInteractions(client);
  }

  @Test
  void missingSourceReferencesAreValidationFailure() throws Exception {
    Files.writeString(root.resolve("source.txt"), "reference text");
    assertEquals(
        ChatGptException.Code.VALIDATION_FAILED,
        backend
            .run(request(List.of("source.txt"), "{\"decision\":\"accept\"}"), owner, true, null)
            .error());
  }

  @Test
  void incompleteAndInterruptedStreamsHaveDifferentStates() {
    for (var state :
        List.of(PlanSseAssembler.State.INCOMPLETE, PlanSseAssembler.State.OUTCOME_UNKNOWN)) {
      when(client.infer(
              anyString(),
              anyString(),
              anyString(),
              anyString(),
              any(),
              anyInt(),
              anyInt(),
              any(),
              any()))
          .thenReturn(new PlanSseAssembler.Result(state, "", null, null, null));
      var receipt = backend.run(request(List.of(), "{\"decision\":\"accept\"}"), owner, true, null);
      assertEquals(
          state == PlanSseAssembler.State.INCOMPLETE
              ? ChatGptSubAgentBackend.State.INCOMPLETE
              : ChatGptSubAgentBackend.State.OUTCOME_UNKNOWN,
          receipt.state());
      assertNotEquals(SubAgentResult.Status.COMPLETED, receipt.result().status());
    }
  }

  @Test
  void timeoutAfterDispatchIsUnknown() {
    when(client.infer(
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            any(),
            anyInt(),
            anyInt(),
            any(),
            any()))
        .thenAnswer(
            call -> {
              if (call.getArgument(8) != null) ((Runnable) call.getArgument(8)).run();
              throw ChatGptException.of(ChatGptException.Code.TIMEOUT);
            });
    var receipt = backend.run(request(List.of(), "{\"decision\":\"accept\"}"), owner, true, null);
    assertEquals(ChatGptSubAgentBackend.State.OUTCOME_UNKNOWN, receipt.state());
    assertEquals(RunStatus.UNKNOWN, runs.get(receipt.requestId()).status());
  }

  @Test
  void suppliedSourceReceiptsAreCheckedAgainstSnapshot() throws Exception {
    Files.writeString(root.resolve("source.txt"), "source observation");
    for (boolean validQuote : List.of(true, false)) {
      doAnswer(
              call -> {
                ((Runnable) call.getArgument(8)).run();
                var json = new com.fasterxml.jackson.databind.ObjectMapper();
                var task = json.readTree((String) call.getArgument(3));
                var source = task.get("sources").get(0);
                var wrapper = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(VALID);
                wrapper.set("scope", task.get("scope"));
                var reference = wrapper.putArray("references").addObject();
                for (String field : List.of("referenceId", "path", "sha256"))
                  reference.set(field, source.get(field));
                reference.put("quote", validQuote ? "source observation" : "invented observation");
                return new PlanSseAssembler.Result(
                    PlanSseAssembler.State.COMPLETED, wrapper.toString(), 17, "resp", null);
              })
          .when(client)
          .infer(
              anyString(),
              anyString(),
              anyString(),
              anyString(),
              any(),
              anyInt(),
              anyInt(),
              any(),
              any());
      var receipt =
          backend.run(
              request(List.of("source.txt"), "{\"decision\":\"accept\"}"), owner, true, null);
      assertEquals(
          validQuote ? ChatGptSubAgentBackend.State.COMPLETED : ChatGptSubAgentBackend.State.FAILED,
          receipt.state());
    }
  }

  @Test
  void preDispatchCancellationIsConfirmedAndPostDispatchCancellationIsUnknown() {
    for (boolean dispatched : List.of(false, true)) {
      doAnswer(
              call -> {
                if (dispatched) ((Runnable) call.getArgument(8)).run();
                throw ChatGptException.of(ChatGptException.Code.CANCELLED);
              })
          .when(client)
          .infer(
              anyString(),
              anyString(),
              anyString(),
              anyString(),
              any(),
              anyInt(),
              anyInt(),
              any(),
              any());
      var receipt = backend.run(request(List.of(), "{\"decision\":\"accept\"}"), owner, true, null);
      assertEquals(
          dispatched
              ? ChatGptSubAgentBackend.State.OUTCOME_UNKNOWN
              : ChatGptSubAgentBackend.State.STOP_CONFIRMED,
          receipt.state());
    }
  }

  @Test
  void existingReviewerRoleAndStrictSchemaAreReusable() throws Exception {
    Files.copy(Path.of("config/subagents/reviewer.yaml"), root.resolve("reviewer.yaml"));
    var roleRegistry =
        new SubAgentRegistry(
            root,
            new SubAgentDefinitionLoader(
                new SubAgentToolPolicy(Set.of("readMultiFile", "grepMultiQuery", "searchAndRead")),
                model -> true));
    assertTrue(roleRegistry.reload().isEmpty());
    var sharedRoleBackend =
        new ChatGptSubAgentBackend(
            client,
            new PlanProperties(true, root, Set.of("reviewer")),
            roleRegistry,
            permissions,
            cancellation,
            new AgentEventFactory(Clock.systemUTC()),
            mock(AgentEventPublisher.class),
            Clock.systemUTC(),
            runs);
    var json = new com.fasterxml.jackson.databind.ObjectMapper();
    var wrapper = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(VALID);
    wrapper
        .withObject("subagent")
        .set("result", json.readTree("{\"findings\":[],\"unverified\":[]}"));
    doReturn(
            new PlanSseAssembler.Result(
                PlanSseAssembler.State.COMPLETED, wrapper.toString(), 17, "resp", null))
        .when(client)
        .infer(
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            any(),
            anyInt(),
            anyInt(),
            any(),
            any());
    var receipt =
        sharedRoleBackend.run(
            new ChatGptSubAgentBackend.Request(
                "reviewer",
                "chatgpt-plan",
                "model",
                "default",
                "Review",
                List.of(),
                "{\"unverified\":[]}"),
            owner,
            true,
            null);
    assertEquals(ChatGptSubAgentBackend.State.COMPLETED, receipt.state());
    assertEquals("reviewer", receipt.roleId());
  }
}
