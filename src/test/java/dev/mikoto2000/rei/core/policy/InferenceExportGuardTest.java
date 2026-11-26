package dev.mikoto2000.rei.core.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.modeloptions.AcceptedRunOptions;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Real local approval SQL; no token, model, SDK, or external service is used. */
class InferenceExportGuardTest {
  @TempDir Path directory;
  private final Clock clock = Clock.fixed(Instant.parse("2026-11-26T00:00:00Z"), ZoneOffset.UTC);

  @Test
  void conversationInferenceUsesAnIndependentSingleUseApproval() {
    var approvals = approvals();
    var guard = new InferenceExportGuard(approvals, clock);
    var connection = connection(true, "a");
    var owner = owner("run", AgentRunContext.Mode.CONVERSATION);
    var prompt = prompt(connection, "PRIVATE FIXTURE QUESTION");
    var pending = new AtomicReference<InferenceExportGuard.Decision>();
    assertThatCode(() -> pending.set(guard.authorize(owner, connection, prompt)))
        .doesNotThrowAnyException();
    assertThat(pending.get().permitted()).isFalse();
    var request = approvals.get(owner.projectId(), pending.get().approvalId());
    assertThat(request.tool()).isEqualTo("inferenceExport");
    assertThat(request.argumentsPreview())
        .contains("fixture-endpoint", "fixture-model")
        .doesNotContain("PRIVATE FIXTURE QUESTION");
    approvals.decide(owner.projectId(), request.id(), true);
    var allowed = guard.authorize(owner, connection, prompt);
    assertThat(allowed.permitted()).isTrue();
    assertThat(allowed.prompt()).isNotSameAs(prompt);
    assertThat(allowed.toString()).doesNotContain("PRIVATE FIXTURE QUESTION");
    assertThat(approvals.get(owner.projectId(), request.id()).status()).isEqualTo("CONSUMED");
  }

  @Test
  void approvedToolActionDoesNotAuthorizeInferenceExport() {
    var approvals = approvals();
    var guard = new InferenceExportGuard(approvals, clock);
    var owner = owner("run", AgentRunContext.Mode.EXCLUSIVE);
    var action = approvals.request("writeMultiFile", "{}", owner);
    approvals.decide(owner.projectId(), action.id(), true);
    var connection = connection(true, "a");
    var pending = new AtomicReference<InferenceExportGuard.Decision>();
    assertThatCode(
            () -> pending.set(guard.authorize(owner, connection, prompt(connection, "fixture"))))
        .doesNotThrowAnyException();
    assertThat(pending.get().permitted()).isFalse();
    assertThat(pending.get().approvalId()).isNotEqualTo(action.id());
    assertThat(approvals.get(owner.projectId(), action.id()).status()).isEqualTo("APPROVED");
  }

  @Test
  void changedContentDestinationOrRunCannotConsumeAnOldApproval() {
    var approvals = approvals();
    var guard = new InferenceExportGuard(approvals, clock);
    var owner = owner("run", AgentRunContext.Mode.READ_ONLY);
    var connection = connection(true, "a");
    var pending = new AtomicReference<InferenceExportGuard.Decision>();
    assertThatCode(() -> pending.set(guard.authorize(owner, connection, prompt(connection, "one"))))
        .doesNotThrowAnyException();
    approvals.decide(owner.projectId(), pending.get().approvalId(), true);
    assertThat(guard.authorize(owner, connection, prompt(connection, "two")).permitted()).isFalse();
    var replacement = connection(true, "b");
    assertThat(guard.authorize(owner, replacement, prompt(replacement, "one")).permitted())
        .isFalse();
    assertThat(
            guard
                .authorize(owner("other-run", owner.mode()), connection, prompt(connection, "one"))
                .permitted())
        .isFalse();
    assertThat(approvals.get(owner.projectId(), pending.get().approvalId()).status())
        .isEqualTo("APPROVED");
    assertThat(guard.authorize(owner, connection, prompt(connection, "one")).permitted()).isTrue();
  }

  @Test
  void legacyConnectionKeepsItsExistingAuthorityWithoutCreatingNewApprovals() {
    var approvals = approvals();
    var guard = new InferenceExportGuard(approvals, clock);
    var owner = owner("run", AgentRunContext.Mode.CONVERSATION);
    var connection = connection(false, "a");
    var result = new AtomicReference<InferenceExportGuard.Decision>();
    assertThatCode(
            () -> result.set(guard.authorize(owner, connection, prompt(connection, "fixture"))))
        .doesNotThrowAnyException();
    assertThat(result.get().permitted()).isTrue();
    assertThat(approvals.list(owner.projectId())).isEmpty();
  }

  private ToolApprovalRepository approvals() {
    return new ToolApprovalRepository(
        new DriverManagerDataSource("jdbc:sqlite:" + directory.resolve("approvals.db")), clock);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(AgentRunContext.Mode.class)
  void configuredInferenceAutoApprovalDoesNotGrantConversationToolExecution(
      AgentRunContext.Mode mode) {
    var approvals = approvals();
    var guard = new InferenceExportGuard(approvals, clock);
    var policy =
        new ToolPermissionPolicy(
            new ToolPermissionProperties(
                true,
                java.util.Set.of(
                    ActionCapability.READ,
                    ActionCapability.NETWORK_WRITE,
                    ActionCapability.EXTERNAL_SIDE_EFFECT),
                java.util.Set.of(),
                Map.of()));
    guard.configurePolicy(policy);
    var owner = owner("run", mode);
    var connection = connection(true, "a");
    var supplied = prompt(connection, "PRIVATE AUTO FIXTURE");
    var result = guard.authorize(owner, connection, supplied);
    assertThat(result.permitted()).isTrue();
    assertThat(result.prompt()).isNotSameAs(supplied);
    assertThat(approvals.list(owner.projectId())).isEmpty();
    if (mode == AgentRunContext.Mode.CONVERSATION) {
      var tools =
          new ToolPermissionGuard(
              policy, new dev.mikoto2000.rei.event.AgentEventFactory(clock), ignored -> {});
      assertThatThrownBy(() -> tools.check("searchTools", "{}", owner))
          .isInstanceOfSatisfying(
              ToolPermissionException.class,
              failure -> assertThat(failure.decision()).isEqualTo(PermissionDecision.DENY));
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(
      value = ActionCapability.class,
      names = {"NETWORK_WRITE", "EXTERNAL_SIDE_EFFECT"})
  void administratorDenialWinsOverAnExistingExactHumanInferenceApproval(ActionCapability denied) {
    var approvals = approvals();
    var guard = new InferenceExportGuard(approvals, clock);
    var owner = owner("run", AgentRunContext.Mode.READ_ONLY);
    var connection = connection(true, "a");
    var prompt = prompt(connection, "fixture");
    var pending = guard.authorize(owner, connection, prompt);
    approvals.decide(owner.projectId(), pending.approvalId(), true);
    guard.configurePolicy(
        new ToolPermissionPolicy(
            new ToolPermissionProperties(
                true,
                java.util.EnumSet.allOf(ActionCapability.class),
                java.util.Set.of(denied),
                Map.of())));
    assertThatThrownBy(() -> guard.authorize(owner, connection, prompt))
        .isInstanceOf(SecurityException.class);
    assertThat(approvals.get(owner.projectId(), pending.approvalId()).status())
        .isEqualTo("APPROVED");
  }

  @Test
  void inferenceCannotBeRelabeledAsAReadToBypassTheExternalSendDenyPolicy() {
    var approvals = approvals();
    var guard = new InferenceExportGuard(approvals, clock);
    guard.configurePolicy(
        new ToolPermissionPolicy(
            new ToolPermissionProperties(
                true,
                java.util.Set.of(ActionCapability.READ),
                java.util.Set.of(ActionCapability.NETWORK_WRITE),
                Map.of(
                    "inferenceExport",
                    java.util.Set.of(ActionCapability.READ),
                    "requestChatGptPlanInference",
                    java.util.Set.of(ActionCapability.READ)))));
    var connection = connection(true, "a");
    assertThatThrownBy(() ->
            guard.authorize(
                owner("run", AgentRunContext.Mode.CONVERSATION),
                connection,
                prompt(connection, "fixture")))
        .isInstanceOf(SecurityException.class);
    assertThat(approvals.list("project")).isEmpty();
  }

  @Test
  void disabledAdministratorPolicyRetainsItsExistingAutomaticDecisionSemantics() {
    var approvals = approvals();
    var guard = new InferenceExportGuard(approvals, clock);
    guard.configurePolicy(
        new ToolPermissionPolicy(new ToolPermissionProperties(false, null, null, null)));
    var connection = connection(true, "a");
    assertThat(
            guard
                .authorize(
                    owner("run", AgentRunContext.Mode.CONVERSATION),
                    connection,
                    prompt(connection, "fixture"))
                .permitted())
        .isTrue();
    assertThat(approvals.list("project")).isEmpty();
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void explicitFullAutoStillCannotOverrideAdministratorDenial(boolean denied) {
    var approvals = approvals();
    var guard = new InferenceExportGuard(approvals, clock);
    guard.configurePolicy(
        new ToolPermissionPolicy(
            new ToolPermissionProperties(
                true,
                null,
                denied ? java.util.Set.of(ActionCapability.NETWORK_WRITE) : java.util.Set.of(),
                Map.of()),
            new dev.mikoto2000.rei.computeruse.FullAutoOptions(
                new org.springframework.boot.DefaultApplicationArguments("--fullauto"))));
    var owner =
        new AgentRunContext(
            "voice-run",
            "session",
            directory,
            "project",
            AgentRunContext.RequestSource.SHELL,
            AgentRunContext.Mode.CONVERSATION,
            true);
    var connection = connection(true, "a");
    if (denied)
      assertThatThrownBy(() -> guard.authorize(owner, connection, prompt(connection, "fixture")))
          .isInstanceOf(SecurityException.class);
    else
      assertThat(guard.authorize(owner, connection, prompt(connection, "fixture")).permitted())
          .isTrue();
    assertThat(approvals.list("project")).isEmpty();
  }

  @Test
  void configuredAutoApprovalWithoutFullAutoStillRequiresExactVoiceInferenceApproval() {
    var approvals = approvals();
    var guard = new InferenceExportGuard(approvals, clock);
    guard.configurePolicy(
        new ToolPermissionPolicy(
            new ToolPermissionProperties(
                true,
                java.util.Set.of(
                    ActionCapability.NETWORK_WRITE, ActionCapability.EXTERNAL_SIDE_EFFECT),
                java.util.Set.of(),
                Map.of())));
    var owner =
        new AgentRunContext(
            "voice-run",
            "session",
            directory,
            "project",
            AgentRunContext.RequestSource.SHELL,
            AgentRunContext.Mode.CONVERSATION,
            true);
    var connection = connection(true, "a");
    var pending = guard.authorize(owner, connection, prompt(connection, "voice fixture"));
    assertThat(pending.permitted()).isFalse();
    assertThat(approvals.get(owner.projectId(), pending.approvalId()).status())
        .isEqualTo("PENDING");
  }

  @Test
  void savedBindingVerificationNeitherGrantsNorConsumesInferenceAuthority() {
    var approvals = approvals();
    var guard = new InferenceExportGuard(approvals, clock);
    var owner = owner("run", AgentRunContext.Mode.CONVERSATION);
    var connection = connection(true, "a");
    var prompt = prompt(connection, "fixture");
    var decision = guard.authorize(owner, connection, prompt);
    var input = InferenceExportRequest.prepare(decision.prompt()).input();
    guard.verifySavedApproval(decision.approvalId(), owner, connection, input);
    assertThat(approvals.get(owner.projectId(), decision.approvalId()).status())
        .isEqualTo("PENDING");
    approvals.decide(owner.projectId(), decision.approvalId(), true);
    guard.verifySavedApproval(decision.approvalId(), owner, connection, input);
    assertThat(approvals.get(owner.projectId(), decision.approvalId()).status())
        .isEqualTo("APPROVED");
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {"purpose", "content", "connection", "owner"})
  void savedInputCannotBeAttachedToAnotherApprovalBinding(String changed) {
    var approvals = approvals();
    var guard = new InferenceExportGuard(approvals, clock);
    var owner = owner("run", AgentRunContext.Mode.READ_ONLY);
    var connection = connection(true, "a");
    var prompt = prompt(connection, "fixture");
    var decision = guard.authorize(owner, connection, prompt);
    var input = InferenceExportRequest.prepare(decision.prompt()).input();
    var id =
        changed.equals("purpose")
            ? approvals.request("writeMultiFile", "{}", owner).id()
            : decision.approvalId();
    var suppliedOwner = changed.equals("owner") ? owner("other-run", owner.mode()) : owner;
    var suppliedConnection = changed.equals("connection") ? connection(true, "b") : connection;
    var suppliedInput =
        changed.equals("content")
            ? InferenceExportRequest.prepare(prompt(connection, "changed")).input()
            : input;
    assertThatThrownBy(
            () -> guard.verifySavedApproval(id, suppliedOwner, suppliedConnection, suppliedInput))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("binding");
    assertThat(approvals.get(owner.projectId(), decision.approvalId()).status())
        .isEqualTo("PENDING");
  }

  private AgentRunContext owner(String id, AgentRunContext.Mode mode) {
    return new AgentRunContext(
        id, "session", directory, "project", AgentRunContext.RequestSource.SHELL, mode);
  }

  private static Prompt prompt(AcceptedRunOptions connection, String text) {
    return new Prompt(
        text,
        ToolCallingChatOptions.builder()
            .model("fixture-model")
            .toolCallbacks(List.of())
            .toolContext(
                Map.of(
                    dev.mikoto2000.rei.modeloptions.ModelOptions.RUN_CONNECTION_CONTEXT_KEY,
                    connection))
            .build());
  }

  private static AcceptedRunOptions connection(boolean named, String identity) {
    return new AcceptedRunOptions() {
      public String fingerprint() {
        return identity.repeat(64);
      }

      public boolean named() {
        return named;
      }

      public boolean supportsOutputLimit() {
        return false;
      }

      public Integer maxOutputTokens() {
        return null;
      }

      public Map<String, String> inferenceDescription() {
        return Map.of(
            "endpoint",
            "fixture-endpoint",
            "provider",
            "fixture-provider",
            "api",
            "fixture-api",
            "authentication",
            "NONE",
            "model",
            "fixture-model",
            "base_url",
            "http://fixture.invalid/v1");
      }
    };
  }
}
