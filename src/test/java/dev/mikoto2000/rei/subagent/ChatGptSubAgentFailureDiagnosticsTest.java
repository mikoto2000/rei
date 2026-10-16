package dev.mikoto2000.rei.subagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.application.run.RunRegistry;
import dev.mikoto2000.rei.application.run.RunStatus;
import dev.mikoto2000.rei.chatgpt.ChatGptException;
import dev.mikoto2000.rei.chatgpt.ChatGptRunFailure;
import dev.mikoto2000.rei.chatgpt.PlanClient;
import dev.mikoto2000.rei.chatgpt.PlanProperties;
import dev.mikoto2000.rei.chatgpt.PlanSseAssembler;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.policy.PermissionDecision;
import dev.mikoto2000.rei.core.policy.ToolPermissionException;
import dev.mikoto2000.rei.core.policy.ToolPermissionGuard;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventType;
import dev.mikoto2000.rei.event.SubAgentLifecyclePayload;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget.LlmCallReservation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ChatGptSubAgentFailureDiagnosticsTest {
  private static final String PRIVATE_PATH = "PRIVATE_SOURCE_PATH";
  private static final String PRIVATE_CONTENT = "PRIVATE_PASSWORD_VALUE";
  @TempDir Path root;
  PlanClient client;
  ToolPermissionGuard permissions;
  RunRegistry runs;
  List<AgentEvent> events;
  ChatGptSubAgentBackend backend;
  AgentRunContext owner;
  LlmCallReservation budget;

  @BeforeEach
  void setup() throws Exception {
    Files.writeString(
        root.resolve("review.yaml"),
        """
        id: design-review
        name: Design
        description: Review
        systemPrompt: Review supplied documents
        tools: []
        maxSteps: 1
        timeout: 120s
        """);
    var registry =
        new SubAgentRegistry(
            root, new SubAgentDefinitionLoader(new SubAgentToolPolicy(Set.of()), model -> true));
    assertThat(registry.reload()).isEmpty();
    var clock = Clock.systemUTC();
    client = mock(PlanClient.class);
    permissions = mock(ToolPermissionGuard.class);
    runs = new RunRegistry(clock);
    owner = new AgentRunContext("parent", "session", root, "project");
    runs.register(owner);
    runs.transition(owner.runId(), RunStatus.RUNNING, null);
    events = new ArrayList<>();
    backend =
        new ChatGptSubAgentBackend(
            client,
            new PlanProperties(true, root, Set.of("design-review")),
            registry,
            permissions,
            new CommandCancellationService(),
            new AgentEventFactory(clock),
            events::add,
            clock,
            runs);
    budget = mock(LlmCallReservation.class);
    when(budget.tryReserve()).thenReturn(true);
  }

  ChatGptSubAgentBackend.Request request(List<String> sources) {
    return new ChatGptSubAgentBackend.Request(
        "design-review",
        "chatgpt-plan",
        "model",
        "default",
        "Review",
        sources,
        "{\"decision\":\"accept\"}");
  }

  void assertSafeFailure(ChatGptSubAgentBackend.Receipt receipt, String... expected) {
    assertThat(receipt.state()).isEqualTo(ChatGptSubAgentBackend.State.FAILED);
    assertThat(receipt.result().status()).isEqualTo(SubAgentResult.Status.FAILED);
    String diagnostic = receipt.result().output();
    assertThat(diagnostic)
        .isNotBlank()
        .matches("^phase=[A-Z_]+; .+")
        .contains(expected)
        .doesNotContain(PRIVATE_PATH, PRIVATE_CONTENT, "PRIVATE_EXCEPTION_VALUE");
    var terminal =
        events.stream().filter(event -> event.type() == AgentEventType.SUBAGENT_FAILED).toList();
    assertThat(terminal).hasSize(1);
    assertThat(terminal.getFirst().runId()).isEqualTo(owner.runId());
    assertThat(((SubAgentLifecyclePayload) terminal.getFirst().payload()).failureReason())
        .isEqualTo(diagnostic);
    assertThat(runs.get(receipt.requestId()).failure().message()).isEqualTo(diagnostic);
    assertThat(events.toString())
        .doesNotContain(PRIVATE_PATH, PRIVATE_CONTENT, "PRIVATE_EXCEPTION_VALUE");
  }

  @Test
  void missingFileReportsItsSourcePositionWithoutPretendingItWasDeniedByPolicy() {
    var receipt = backend.run(request(List.of(PRIVATE_PATH + ".md")), owner, false, budget);
    assertThat(receipt.error()).isEqualTo(ChatGptException.Code.SOURCE_UNAVAILABLE);
    assertSafeFailure(receipt, "NOT_FOUND", "sourceRefs[0]");
    verifyNoInteractions(client);
  }

  @Test
  void invalidUtf8IsReportedAsUnreadableSourceBeforeInference() throws Exception {
    Files.write(root.resolve(PRIVATE_PATH + ".md"), new byte[] {(byte) 0xC3, (byte) 0x28});
    var receipt = backend.run(request(List.of(PRIVATE_PATH + ".md")), owner, false, budget);
    assertThat(receipt.error()).isEqualTo(ChatGptException.Code.SOURCE_UNAVAILABLE);
    assertSafeFailure(receipt, "INVALID_UTF8", "sourceRefs[0]");
    verifyNoInteractions(client);
  }

  @Test
  void examplePasswordIsMaskedBeforeDispatchAndUncitedResponseStillFailsValidation()
      throws Exception {
    String original = "Example configuration:\nspring.datasource.password=" + PRIVATE_CONTENT;
    Files.writeString(root.resolve("example.md"), original);
    doAnswer(
            call -> {
              ((Runnable) call.getArgument(8)).run();
              return new PlanSseAssembler.Result(
                  PlanSseAssembler.State.COMPLETED,
                  ChatGptSubAgentBackendTest.VALID,
                  17,
                  "response",
                  null);
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
    var receipt = backend.run(request(List.of("example.md")), owner, false, budget);
    assertThat(receipt.error()).isEqualTo(ChatGptException.Code.VALIDATION_FAILED);
    assertSafeFailure(receipt, "VALIDATION_FAILED");
    var task = org.mockito.ArgumentCaptor.forClass(String.class);
    verify(client)
        .infer(
            anyString(),
            anyString(),
            anyString(),
            task.capture(),
            any(),
            anyInt(),
            anyInt(),
            any(),
            any());
    assertThat(task.getValue()).contains("[REDACTED]").doesNotContain(PRIVATE_CONTENT);
    var source =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(task.getValue())
            .path("sources")
            .get(0);
    assertThat(source.path("redacted").asBoolean()).isTrue();
    assertThat(Files.readString(root.resolve("example.md"))).isEqualTo(original);
  }

  @Test
  void escapingSourcePathRemainsBlockedWithAPositionOnlyDiagnostic() {
    var receipt = backend.run(request(List.of("../" + PRIVATE_PATH + ".md")), owner, false, budget);
    assertThat(receipt.error()).isEqualTo(ChatGptException.Code.PERMISSION_DENIED);
    assertSafeFailure(receipt, "PATH_NOT_ALLOWED", "sourceRefs[0]");
    verifyNoInteractions(client);
  }

  @Test
  void oversizedSourceHasItsOwnSendLimitDiagnostic() throws Exception {
    Files.writeString(root.resolve(PRIVATE_PATH + ".md"), "x".repeat(262145));
    var receipt = backend.run(request(List.of(PRIVATE_PATH + ".md")), owner, false, budget);
    assertThat(receipt.error()).isEqualTo(ChatGptException.Code.SEND_LIMIT);
    assertSafeFailure(receipt, "TOO_LARGE", "sourceRefs[0]");
    verifyNoInteractions(client);
  }

  @ParameterizedTest
  @EnumSource(
      value = PermissionDecision.class,
      names = {"DENY", "REQUIRE_APPROVAL"})
  void innerSendPermissionFailureIsDistinguishedFromSourceOrModelProblems(
      PermissionDecision decision) {
    var refusal = new ToolPermissionException("requestChatGptPlanSubAgent", decision);
    if (decision == PermissionDecision.REQUIRE_APPROVAL)
      refusal.withApprovalId("1a926efb-6a4f-4c75-b950-6beb64a8ba7e");
    doThrow(refusal)
        .when(permissions)
        .check(eq("requestChatGptPlanSubAgent"), anyString(), eq(owner));
    var receipt = backend.run(request(List.of()), owner, false, budget);
    assertThat(receipt.error())
        .isEqualTo(
            decision == PermissionDecision.DENY
                ? ChatGptException.Code.PERMISSION_DENIED
                : ChatGptException.Code.CONSENT_REQUIRED);
    assertSafeFailure(
        receipt,
        decision == PermissionDecision.DENY ? "TOOL_PERMISSION_DENIED" : "TOOL_APPROVAL_REQUIRED");
    verifyNoInteractions(client);
    verify(budget, never()).tryReserve();
  }

  @Test
  void exhaustedSharedBudgetReportsTheBudgetCauseBeforeAnyExternalCall() {
    when(budget.tryReserve()).thenReturn(false);
    var receipt = backend.run(request(List.of()), owner, false, budget);
    assertThat(receipt.error()).isEqualTo(ChatGptException.Code.PERMISSION_DENIED);
    assertSafeFailure(receipt, "SHARED_LLM_CALL_BUDGET_EXHAUSTED");
    verifyNoInteractions(client);
  }

  @Test
  void exhaustedTokensAndUnknownUsageAreDifferentPredispatchFailures() {
    when(budget.tokenExhausted()).thenReturn(true);
    var receipt = backend.run(request(List.of()), owner, false, budget);
    assertSafeFailure(receipt, "SHARED_TOKEN_BUDGET_EXHAUSTED");
    verifyNoInteractions(client);
    events.clear();
    when(budget.tokenExhausted()).thenReturn(false);
    when(budget.usageUnknown()).thenReturn(true);
    receipt = backend.run(request(List.of()), owner, false, budget);
    assertThat(receipt.error()).isEqualTo(ChatGptException.Code.USAGE_UNAVAILABLE);
    assertSafeFailure(receipt, "SHARED_USAGE_UNKNOWN");
    verifyNoInteractions(client);
    verify(budget, never()).tryReserve();
  }

  @Test
  void sourceReadPermissionIsNotMisclassifiedAsAnUnreadableFile() throws Exception {
    Files.writeString(root.resolve(PRIVATE_PATH + ".md"), "Public source");
    doThrow(new ToolPermissionException("readFile", PermissionDecision.DENY))
        .when(permissions)
        .check(eq("readFile"), anyString(), eq(owner));
    var receipt = backend.run(request(List.of(PRIVATE_PATH + ".md")), owner, false, budget);
    assertSafeFailure(receipt, "SOURCE_READ", "TOOL_PERMISSION_DENIED");
    assertThat(receipt.result().output()).doesNotContain("READ_FAILED");
    verifyNoInteractions(client);
  }

  @Test
  void explicitSanitizedCopyCanBeReviewedWithoutSilentlyRedactingOriginalDocuments()
      throws Exception {
    String content = "spring.datasource.password=[REDACTED]";
    Files.writeString(root.resolve("sanitized.md"), content);
    doAnswer(
            call -> {
              ((Runnable) call.getArgument(8)).run();
              var json = new com.fasterxml.jackson.databind.ObjectMapper();
              var task = json.readTree((String) call.getArgument(3));
              var source = task.path("sources").get(0);
              assertThat(source.path("content").asText()).isEqualTo(content);
              assertThat(task.toString()).doesNotContain(PRIVATE_CONTENT);
              var wrapper =
                  (com.fasterxml.jackson.databind.node.ObjectNode)
                      json.readTree(ChatGptSubAgentBackendTest.VALID);
              wrapper.set("scope", task.path("scope"));
              var reference = wrapper.putArray("references").addObject();
              for (String field : List.of("referenceId", "path", "sha256"))
                reference.set(field, source.get(field));
              reference.put("quote", content);
              return new PlanSseAssembler.Result(
                  PlanSseAssembler.State.COMPLETED, wrapper.toString(), 17, "response", null);
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
    var receipt = backend.run(request(List.of("sanitized.md")), owner, false, budget);
    assertThat(receipt.state()).isEqualTo(ChatGptSubAgentBackend.State.COMPLETED);
    assertThat(receipt.result().status()).isEqualTo(SubAgentResult.Status.COMPLETED);
    assertThat(Files.readString(root.resolve("sanitized.md"))).isEqualTo(content);
    verify(client, times(1))
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
  void credentialLiteralInExpectedConditionsIsStillRejectedBeforeAnyChildOrInference() {
    var request =
        new ChatGptSubAgentBackend.Request(
            "design-review",
            "chatgpt-plan",
            "model",
            "default",
            "Review",
            List.of(),
            "{\"password\":\"" + PRIVATE_CONTENT + "\"}");
    assertThatThrownBy(() -> backend.run(request, owner, false, budget))
        .isInstanceOfSatisfying(
            ChatGptException.class,
            failure -> {
              assertThat(failure.code()).isEqualTo(ChatGptException.Code.PERMISSION_DENIED);
              assertThat(failure.getMessage())
                  .contains("REQUEST_CONTENT_RESTRICTED")
                  .doesNotContain(PRIVATE_CONTENT);
            });
    verifyNoInteractions(client);
    assertThat(events).isEmpty();
    assertThat(runs.runIds()).containsExactly("parent");
  }

  @Test
  void unicodeEscapedCredentialKeysAndValuesInExpectedConditionsAreRejectedAfterParsing() {
    for (String expected :
        List.of(
            "{\"pass\\u0077ord\":\"" + PRIVATE_CONTENT + "\"}",
            "{\"conclusion\":\"pass\\u0077ord=" + PRIVATE_CONTENT + "\"}")) {
      var request =
          new ChatGptSubAgentBackend.Request(
              "design-review", "chatgpt-plan", "model", "default", "Review", List.of(), expected);
      assertThatThrownBy(() -> backend.run(request, owner, false, budget))
          .isInstanceOfSatisfying(
              ChatGptException.class,
              failure -> {
                assertThat(failure.code()).isEqualTo(ChatGptException.Code.PERMISSION_DENIED);
                assertThat(failure.getMessage())
                    .contains("REQUEST_CONTENT_RESTRICTED")
                    .doesNotContain(PRIVATE_CONTENT);
              });
    }
    verifyNoInteractions(client);
    assertThat(events).isEmpty();
    assertThat(runs.runIds()).containsExactly("parent");
  }

  @Test
  void credentialAssignmentInTheCanonicalSourcePathIsNotSentEvenWithPublicFileContents()
      throws Exception {
    String name = "token=" + PRIVATE_CONTENT + ".md";
    Files.writeString(root.resolve(name), "Public document");
    var receipt = backend.run(request(List.of(name)), owner, false, budget);
    assertSafeFailure(receipt, "PATH_NOT_ALLOWED", "sourceRefs[0]");
    verifyNoInteractions(client);
    verify(permissions, never()).check(eq("readFile"), anyString(), eq(owner));
  }

  @Test
  void terminalAccountingFailureDoesNotReplaceThePrimaryAuthenticationCause() {
    doThrow(ChatGptException.of(ChatGptException.Code.AUTH_INVALID))
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
    doThrow(new IllegalStateException("PRIVATE_EXCEPTION_VALUE billing details"))
        .when(budget)
        .recordTotalTokens(any());
    var receipt = backend.run(request(List.of()), owner, false, budget);
    assertThat(receipt.error()).isEqualTo(ChatGptException.Code.AUTH_INVALID);
    assertSafeFailure(receipt, "AUTH_INVALID", "BUDGET_ACCOUNTING");
    verify(client, times(1))
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
  void controlledPermissionAndAuthenticationDiagnosticsAreKeptWithoutRetrying() {
    doThrow(ChatGptException.permission(ChatGptException.PermissionReason.MODEL_NOT_ALLOWED))
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
    var receipt = backend.run(request(List.of()), owner, false, budget);
    assertSafeFailure(
        receipt, "MODEL_NOT_ALLOWED", "rei.chatgpt-plan.auto-delegation.allowed-models");
    verify(client, times(1))
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
    reset(client);
    events.clear();
    doThrow(ChatGptException.of(ChatGptException.Code.AUTH_INVALID))
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
    receipt = backend.run(request(List.of()), owner, false, budget);
    assertThat(receipt.error()).isEqualTo(ChatGptException.Code.AUTH_INVALID);
    assertSafeFailure(receipt, "AUTH_INVALID");
    verify(client, times(1))
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
  void arbitraryPreparationFailureDoesNotBecomePermissionDeniedOrExposeItsMessage() {
    doThrow(new IllegalStateException("PRIVATE_EXCEPTION_VALUE at " + root.resolve(PRIVATE_PATH)))
        .when(permissions)
        .check(eq("requestChatGptPlanSubAgent"), anyString(), eq(owner));
    var receipt = backend.run(request(List.of()), owner, false, budget);
    assertThat(receipt.error()).isEqualTo(ChatGptException.Code.PREPARATION_FAILED);
    assertSafeFailure(receipt, "PREPARATION_FAILED");
    assertThat(receipt.result().output()).doesNotContain("PERMISSION_DENIED");
    verifyNoInteractions(client);
  }

  @ParameterizedTest
  @EnumSource(ChatGptException.SourceFailure.class)
  void sourceFactoriesContainOnlyControlledClassificationAndSourcePosition(
      ChatGptException.SourceFailure reason) {
    var failure = ChatGptException.source(reason, 3);
    assertThat(failure.getMessage()).contains(reason.name(), "sourceRefs[3]");
    assertThat(failure.code())
        .isEqualTo(
            switch (reason) {
              case PATH_NOT_ALLOWED, CONTENT_RESTRICTED -> ChatGptException.Code.PERMISSION_DENIED;
              case TOO_LARGE -> ChatGptException.Code.SEND_LIMIT;
              case NOT_FOUND, READ_FAILED, INVALID_UTF8 -> ChatGptException.Code.SOURCE_UNAVAILABLE;
            });
    assertThat(ChatGptRunFailure.diagnostic(failure).message())
        .contains(reason.name(), "sourceRefs[3]");
  }

  @Test
  void externalFailureReasonsAreRedactedBeforeClippingAndNormalizedConsistently() {
    var factory = new AgentEventFactory(Clock.systemUTC());
    String reason =
        "phase=SOURCE_READ;\n"
            + "a".repeat(140)
            + "\t\u001b\n-----BEGIN PRIVATE KEY-----\n"
            + "PRIVATE_EXCEPTION_VALUE".repeat(120)
            + "\n-----END PRIVATE KEY-----\nafter-key "
            + "public ".repeat(200);
    String normalized = factory.subAgentFailureReason(reason);
    assertThat(normalized)
        .hasSize(1000)
        .endsWith("…")
        .contains("[REDACTED]", "after-key")
        .doesNotContain(
            "PRIVATE_EXCEPTION_VALUE", "-----BEGIN PRIVATE KEY-----", "\n", "\t", "\u001b");
    assertThat(factory.subAgentFailureReason(normalized)).isEqualTo(normalized);
    var external =
        (SubAgentLifecyclePayload)
            factory
                .subAgentLifecycle(
                    AgentEventType.SUBAGENT_FAILED,
                    "parent",
                    "child",
                    "reviewer",
                    "Review",
                    "FAILED",
                    0,
                    reason,
                    "chatgpt-plan",
                    "model",
                    null)
                .payload();
    assertThat(external.failureReason()).isEqualTo(normalized);
    var legacy =
        (SubAgentLifecyclePayload)
            factory
                .subAgentLifecycle(
                    AgentEventType.SUBAGENT_FAILED,
                    "parent",
                    "child",
                    "reviewer",
                    "Review",
                    "FAILED",
                    0,
                    reason)
                .payload();
    assertThat(legacy.failureReason()).hasSize(120).doesNotContain("PRIVATE_EXCEPTION_VALUE");
  }
}
