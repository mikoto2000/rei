package dev.mikoto2000.rei.externalagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.policy.ActionCapability;
import dev.mikoto2000.rei.core.policy.PermissionDecision;
import dev.mikoto2000.rei.core.policy.ToolApprovalRepository;
import dev.mikoto2000.rei.core.policy.ToolPermissionPolicy;
import dev.mikoto2000.rei.core.policy.ToolPermissionProperties;
import dev.mikoto2000.rei.core.stagnation.RunExecutionContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

/** 実装要求サービスの結果と状態の契約を検証するテスト。 */
@Tag("integration")
class ImplementationRequestServiceTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path temporary;

  /** 検証に使う作業ルート。 */
  Path root;

  /** 検証に使う保存と検索の窓口。 */
  ImplementationRequestRepository repository;

  /** 検証に使う承認一覧。 */
  ToolApprovalRepository approvals;

  /** 検証に使うこの機能の動作設定。 */
  CodexProperties properties;

  /** 検証に使う委譲。 */
  ExternalAgentDelegationService delegation;

  /** 検証に使うサービス。 */
  ImplementationRequestService service;

  /** 検証に使う実行。 */
  RunExecutionContext run;

  /** 検証に使う仕様。 */
  ImplementationSpecification specification;

  /** 各検証を独立した状態で始められるよう、依存先と検証データを準備する。 */
  @BeforeEach
  void setup() throws Exception {
    root = Files.createDirectory(temporary.resolve("project"));
    git("init", "--quiet");
    Files.writeString(root.resolve("A.txt"), "before\n");
    git("add", "A.txt");
    git("commit", "--quiet", "--no-gpg-sign", "-m", "fixture");
    var source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + temporary.resolve("requests.db"));
    repository = new ImplementationRequestRepository(source, Clock.systemUTC());
    approvals = new ToolApprovalRepository(source, Clock.systemUTC());
    properties = new CodexProperties();
    properties.setImplementationEnabled(true);
    properties.setImplementationTestCommand("exit 0");
    delegation = mock(ExternalAgentDelegationService.class);
    run =
        new RunExecutionContext(
            "run", new dev.mikoto2000.rei.llm.OutputLimitRunBudget(2, 10), null, null, null);
    run.setRunContext(new AgentRunContext("run", "session", root, "project"));
    run.setUserRequest("Codex で A.txt を実装してください");
    specification =
        new ImplementationSpecification(
            1,
            "Improve greeting",
            List.of("Replace before with after"),
            "A.txt",
            List.of("A.txt"),
            List.of("No new files"),
            List.of(
                new ImplementationSpecification.AcceptanceCriterion(
                    "greeting", "Greeting says after")),
            List.of(),
            "REPLACE_EXISTING_TEXT");
    policy(Set.of(ActionCapability.values()), Set.of(), true);
  }

  /** 検証で使用する判断基準を、指定された条件に合わせて用意する。 */
  void policy(Set<ActionCapability> automatic, Set<ActionCapability> denied, boolean enabled) {
    service =
        new ImplementationRequestService(
            repository,
            approvals,
            new ToolPermissionPolicy(
                new ToolPermissionProperties(enabled, automatic, denied, Map.of())),
            properties,
            delegation,
            Clock.systemUTC());
  }

  /** 外部エージェントへの委譲の操作が、指定された権限・承認条件に従うことを確認する。 */
  @Test
  void policyAuthorizesOnlyActualHumanImplementationAndOptIn() {
    var ready = service.prepare(run, specification, null);
    assertEquals("AUTHORIZED", ready.status());
    assertFalse(ready.approvalRequired());
    run.setUserRequest("Please review A.txt");
    assertEquals("REJECTED", service.prepare(run, specification, null).status());
    run.setUserRequest("Codex で A.txt を実装してください");
    properties.setImplementationEnabled(false);
    assertEquals("REJECTED", service.prepare(run, specification, null).status());
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(delegation);
  }

  /** 外部エージェントへの委譲で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void disabledPolicyDoesNotImplicitlyGrantAutomaticExecutionAndDenialWins() {
    policy(Set.of(ActionCapability.values()), Set.of(), false);
    assertEquals("AWAITING_APPROVAL", service.prepare(run, specification, null).status());
    policy(Set.of(), Set.of(ActionCapability.EXECUTE), true);
    run.setUserRequest("Codex で A.txt を実装してください。もう一度");
    assertEquals("REJECTED", service.prepare(run, specification, null).status());
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(delegation);
  }

  /** 外部エージェントへの委譲の操作が、指定された権限・承認条件に従うことを確認する。 */
  @Test
  void explicitApprovalBindsCanonicalSpecificationAndSurvivesNewRun() throws Exception {
    policy(Set.of(ActionCapability.READ), Set.of(), true);
    var prepared = service.prepare(run, specification, null);
    assertEquals("AWAITING_APPROVAL", prepared.status());
    assertEquals(
        "AWAITING_APPROVAL",
        service
            .execute(
                run,
                prepared.requestId(),
                prepared.specificationVersion(),
                prepared.specificationSha256())
            .status());
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(delegation);
    var approval = approvals.list("project").getFirst();
    assertTrue(approval.argumentsPreview().contains("Replace before with after"));
    assertTrue(approval.argumentsPreview().contains("Greeting says after"));
    approvals.decide("project", approval.id(), true);
    run.setRunContext(new AgentRunContext("resume", "session", root, "project"));
    run.setUserRequest("保存されたタスクを再開してください");
    when(delegation.executeSpecification(
            any(), anyString(), any(), anyString(), anyString(), anyInt()))
        .thenReturn(receipt("READY_FOR_APPROVAL"));
    var result = service.execute(run, prepared.requestId(), 1, prepared.specificationSha256());
    assertEquals("RESULT_AVAILABLE", result.status());
    assertEquals("NOT_VERIFIED", result.acceptanceResults().getFirst().status());
    assertEquals(
        result, service.execute(run, prepared.requestId(), 1, prepared.specificationSha256()));
    verify(delegation, times(1))
        .executeSpecification(any(), anyString(), any(), anyString(), anyString(), anyInt());
  }

  /** 外部エージェントへの委譲で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void alteredArgumentsProjectOrRecipeNeverExecute() {
    var prepared = service.prepare(run, specification, null);
    assertThrows(
        IllegalArgumentException.class,
        () -> service.execute(run, prepared.requestId(), 2, prepared.specificationSha256()));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.execute(run, prepared.requestId(), 1, "a".repeat(64)));
    run.setRunContext(new AgentRunContext("run", "session", root, "other"));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.execute(run, prepared.requestId(), 1, prepared.specificationSha256()));
    run.setRunContext(new AgentRunContext("run", "session", root, "project"));
    properties.setImplementationTestCommand("different");
    assertThrows(
        IllegalArgumentException.class,
        () -> service.execute(run, prepared.requestId(), 1, prepared.specificationSha256()));
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(delegation);
  }

  /** 外部エージェントへの委譲の操作が、指定された権限・承認条件に従うことを確認する。 */
  @Test
  void unknownIsNeverRetriedAndSuccessorRequiresExplicitApprovalEvenUnderAutomaticPolicy()
      throws Exception {
    var prepared = service.prepare(run, specification, null);
    repository.claim(prepared.requestId(), "dead-jvm");
    assertEquals(
        "UNKNOWN",
        service.execute(run, prepared.requestId(), 1, prepared.specificationSha256()).status());
    verify(delegation, never())
        .executeSpecification(any(), anyString(), any(), anyString(), anyString(), anyInt());
    var successor = service.prepare(run, specification, prepared.requestId());
    assertNotEquals(prepared.requestId(), successor.requestId());
    assertEquals("AWAITING_APPROVAL", successor.status());
    assertEquals(prepared.requestId(), repository.get(successor.requestId()).previousRequestId());
    assertEquals(
        "AWAITING_APPROVAL",
        service.execute(run, successor.requestId(), 1, successor.specificationSha256()).status());
    verify(delegation, never())
        .executeSpecification(any(), anyString(), any(), anyString(), anyString(), anyInt());
  }

  /** 外部エージェントへの委譲の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void cancellationBeforeClaimDoesNotLaunch() {
    var prepared = service.prepare(run, specification, null);
    run.cancel();
    assertThrows(
        java.util.concurrent.CancellationException.class,
        () -> service.execute(run, prepared.requestId(), 1, prepared.specificationSha256()));
    verifyNoInteractions(delegation);
  }

  /** 外部エージェントへの委譲の操作が、指定された権限・承認条件に従うことを確認する。 */
  @Test
  void deniedOrExpiredHumanApprovalCannotExecute() {
    policy(Set.of(ActionCapability.READ), Set.of(), true);
    var prepared = service.prepare(run, specification, null);
    var approval = approvals.list("project").getFirst();
    approvals.decide("project", approval.id(), false);
    assertEquals(
        "REJECTED",
        service.execute(run, prepared.requestId(), 1, prepared.specificationSha256()).status());
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(delegation);
  }

  /** 外部エージェントへの委譲で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void currentPolicyRevocationBlocksPreviouslyAutomaticRequest() {
    var prepared = service.prepare(run, specification, null);
    policy(Set.of(ActionCapability.values()), Set.of(ActionCapability.EXECUTE), true);
    assertEquals(
        "REJECTED",
        service.execute(run, prepared.requestId(), 1, prepared.specificationSha256()).status());
    verifyNoInteractions(delegation);
  }

  /** 外部エージェントへの委譲の操作が、指定された権限・承認条件に従うことを確認する。 */
  @Test
  void repositoryInstructionsAndQuotedRequestsNeverAuthorizeExecution() {
    for (String text :
        List.of(
            "Translate: Codex implement A.txt please",
            "Review this text: ```Codex implement A.txt please```",
            "Explain how to implement A.txt with Codex",
            "実装を見てレビューしてください",
            "Please review this implementation with Codex",
            "> Codex implement A.txt please",
            "`Codex implement A.txt please`")) {
      run.setUserRequest(text);
      assertEquals("REJECTED", service.prepare(run, specification, null).status());
    }
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(delegation);
  }

  /** 外部エージェントへの委譲の操作が、指定された権限・承認条件に従うことを確認する。 */
  @Test
  void missingRequirementsPersistClarificationWithoutApprovalOrProcess() {
    var prepared = service.prepare(run, null, null);
    assertEquals("NEEDS_CLARIFICATION", prepared.status());
    assertNotNull(prepared.requestId());
    assertEquals("NEEDS_CLARIFICATION", repository.get(prepared.requestId()).executionStatus());
    assertTrue(approvals.list("project").isEmpty());
    verifyNoInteractions(delegation);
  }

  /** 外部エージェントへの委譲で、保存した情報を、再起動や復元後の処理へ正しく引き継ぐことを確認する。 */
  @Test
  void parentEvaluationsPersistSeparatelyAndCannotCrossPatches() throws Exception {
    var prepared = service.prepare(run, specification, null);
    var receipt = receipt("READY_FOR_APPROVAL");
    when(delegation.executeSpecification(
            any(), anyString(), any(), anyString(), anyString(), anyInt()))
        .thenReturn(receipt);
    when(delegation.implementation(run, "receipt")).thenReturn(receipt);
    var result = service.execute(run, prepared.requestId(), 1, prepared.specificationSha256());
    var evaluation =
        new AcceptanceEvaluation(
            "greeting",
            "FAILED",
            List.of("receipt:receipt", "patch:" + receipt.patchHash()),
            "Diff still has old greeting",
            "PARENT_LLM",
            receipt.patchHash());
    assertEquals(
        "FAILED",
        service
            .evaluate(run, prepared.requestId(), receipt.patchHash(), List.of(evaluation))
            .acceptanceResults()
            .getFirst()
            .status());
    assertEquals(
        "FAILED", service.get(run, prepared.requestId()).acceptanceResults().getFirst().status());
    assertEquals("RESULT_AVAILABLE", repository.get(prepared.requestId()).executionStatus());
    assertThrows(
        IllegalArgumentException.class,
        () -> service.evaluate(run, prepared.requestId(), "x".repeat(64), List.of(evaluation)));
  }

  /** 再開済み不明試行について、終端受付記録を読み取ることを確認する。 */
  @Test
  void restartedUnknownAttemptReadsTerminalReceiptWithoutRelaunching() throws Exception {
    var prepared = service.prepare(run, specification, null);
    repository.claim(prepared.requestId(), "dead-jvm");
    var source = receipt("READY_FOR_APPROVAL");
    var terminal =
        new IsolatedImplementationService.Receipt(
            source.id(),
            source.projectId(),
            source.sessionId(),
            source.root(),
            source.worktree(),
            source.branch(),
            repository.get(prepared.requestId()).baseCommit(),
            source.status(),
            source.patchHash(),
            source.commitHash(),
            source.changedFiles(),
            source.sourceSnapshot(),
            source.verification(),
            source.diagnostic());
    when(delegation.implementation(run, prepared.requestId())).thenReturn(terminal);
    var result = service.execute(run, prepared.requestId(), 1, prepared.specificationSha256());
    assertEquals("RESULT_AVAILABLE", result.status());
    assertEquals("NOT_VERIFIED", result.acceptanceResults().getFirst().status());
    verify(delegation, never())
        .executeSpecification(any(), anyString(), any(), anyString(), anyString(), anyInt());
  }

  /** 外部エージェントへの委譲で、競合する要求でも、実行順序と作業の分離を守ることを確認する。 */
  @Test
  void concurrentRequestsAcrossServiceInstancesLaunchOnlyOneProvider() throws Exception {
    var prepared = service.prepare(run, specification, null);
    var started = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    when(delegation.executeSpecification(
            any(), anyString(), any(), anyString(), anyString(), anyInt()))
        .thenAnswer(
            call -> {
              started.countDown();
              assertTrue(release.await(10, java.util.concurrent.TimeUnit.SECONDS));
              return receipt("READY_FOR_APPROVAL");
            });
    var other =
        new ImplementationRequestService(
            repository,
            approvals,
            new ToolPermissionPolicy(
                new ToolPermissionProperties(
                    true, Set.of(ActionCapability.values()), Set.of(), Map.of())),
            properties,
            delegation,
            Clock.systemUTC());
    try (var pool = java.util.concurrent.Executors.newSingleThreadExecutor()) {
      var first =
          pool.submit(
              () -> service.execute(run, prepared.requestId(), 1, prepared.specificationSha256()));
      try {
        assertTrue(started.await(10, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(
            "UNKNOWN",
            other.execute(run, prepared.requestId(), 1, prepared.specificationSha256()).status());
      } finally {
        release.countDown();
      }
      assertEquals(
          "RESULT_AVAILABLE", first.get(10, java.util.concurrent.TimeUnit.SECONDS).status());
    }
    verify(delegation, times(1))
        .executeSpecification(any(), anyString(), any(), anyString(), anyString(), anyInt());
  }

  /** 外部エージェントへの委譲の操作が、指定された権限・承認条件に従うことを確認する。 */
  @Test
  void expiredApprovalCannotAuthorizeARequest() throws Exception {
    policy(Set.of(ActionCapability.READ), Set.of(), true);
    var prepared = service.prepare(run, specification, null);
    approvals.decide("project", approvals.list("project").getFirst().id(), true);
    var source =
        new org.springframework.jdbc.datasource.DriverManagerDataSource(
            "jdbc:sqlite:" + temporary.resolve("requests.db"));
    var expired =
        new ToolApprovalRepository(source, Clock.offset(Clock.systemUTC(), Duration.ofMinutes(16)));
    service =
        new ImplementationRequestService(
            repository,
            expired,
            new ToolPermissionPolicy(
                new ToolPermissionProperties(
                    true, Set.of(ActionCapability.READ), Set.of(), Map.of())),
            properties,
            delegation,
            Clock.systemUTC());
    assertEquals(
        "AWAITING_APPROVAL",
        service.execute(run, prepared.requestId(), 1, prepared.specificationSha256()).status());
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(delegation);
  }

  /** 外部エージェントへの委譲で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void humanDenialRemainsBindingEvenIfPolicyLaterBecomesAutomatic() {
    policy(Set.of(ActionCapability.READ), Set.of(), true);
    var prepared = service.prepare(run, specification, null);
    approvals.decide("project", approvals.list("project").getFirst().id(), false);
    policy(Set.of(ActionCapability.values()), Set.of(), true);
    assertEquals(
        "REJECTED",
        service.execute(run, prepared.requestId(), 1, prepared.specificationSha256()).status());
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(delegation);
  }

  /** 外部エージェントへの委譲で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void slashCommandTargetCannotBeSubstitutedByTheModel() throws Exception {
    Files.writeString(root.resolve("B.txt"), "other");
    run.setUserRequest("/agent codex implement B.txt");
    assertEquals("REJECTED", service.prepare(run, specification, null).status());
    verifyNoInteractions(delegation);
  }

  /** 外部エージェントへの委譲で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void executionUsesApprovedRecipeEvenIfAdministratorConfigurationChangesDuringPolicyCheck()
      throws Exception {
    var checks = new java.util.concurrent.atomic.AtomicInteger();
    var changingPolicy =
        new ToolPermissionPolicy(
            new ToolPermissionProperties(
                true, Set.of(ActionCapability.values()), Set.of(), Map.of())) {
          /** {@code evaluate} の条件を再現するための補助情報を返す。 */
          @Override
          public PermissionDecision evaluate(String tool) {
            if (checks.incrementAndGet() == 2)
              properties.setImplementationTestCommand("unapproved recipe");
            return super.evaluate(tool);
          }
        };
    service =
        new ImplementationRequestService(
            repository, approvals, changingPolicy, properties, delegation, Clock.systemUTC());
    var prepared = service.prepare(run, specification, null);
    when(delegation.executeSpecification(
            any(), anyString(), any(), anyString(), anyString(), anyInt()))
        .thenAnswer(
            call -> {
              assertEquals("exit 0", call.getArgument(4));
              return receipt("READY_FOR_APPROVAL");
            });
    service.execute(run, prepared.requestId(), 1, prepared.specificationSha256());
  }

  /** 外部エージェントへの委譲の操作が、指定された権限・承認条件に従うことを確認する。 */
  @Test
  void pendingManualApprovalCannotBeSkippedByLaterAutomaticPolicy() throws Exception {
    policy(Set.of(ActionCapability.READ), Set.of(), true);
    var prepared = service.prepare(run, specification, null);
    policy(Set.of(ActionCapability.values()), Set.of(), true);
    when(delegation.executeSpecification(
            any(), anyString(), any(), anyString(), anyString(), anyInt()))
        .thenReturn(receipt("READY_FOR_APPROVAL"));
    assertEquals(
        "AWAITING_APPROVAL",
        service.execute(run, prepared.requestId(), 1, prepared.specificationSha256()).status());
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(delegation);
  }

  /** 外部エージェントへの委譲で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void savedClarificationCarriesActualUserIntentIntoAConfirmedFollowup() throws Exception {
    var draft = service.prepare(run, null, null);
    assertEquals("NEEDS_CLARIFICATION", draft.status());
    run.setRunContext(new AgentRunContext("followup", "session", root, "project"));
    run.setUserRequest("対象は A.txt。指示は before を after に置換。条件は Greeting says after です。");
    var prepared = service.prepare(run, specification, null, draft.requestId());
    assertEquals("AUTHORIZED", prepared.status());
    assertNotEquals(draft.requestId(), prepared.requestId());
    assertTrue(
        repository.get(prepared.requestId()).originUserMessageId().contains(draft.requestId()));
    when(delegation.executeSpecification(
            any(), anyString(), any(), anyString(), anyString(), anyInt()))
        .thenReturn(receipt("READY_FOR_APPROVAL"));
    assertEquals(
        "RESULT_AVAILABLE",
        service.execute(run, prepared.requestId(), 1, prepared.specificationSha256()).status());
  }

  /** 元の対象を維持することを確認する。 */
  @Test
  void slashClarificationKeepsTheOriginalTargetAndDoesNotTreatUnrelatedReviewAsConfirmation()
      throws Exception {
    run.setUserRequest("/agent codex implement A.txt");
    var draft = service.prepare(run, null, null);
    run.setRunContext(new AgentRunContext("followup", "session", root, "project"));
    run.setUserRequest("Please review the implementation");
    assertEquals("REJECTED", service.prepare(run, specification, null, draft.requestId()).status());
    Files.writeString(root.resolve("B.txt"), "other");
    run.setUserRequest("対象は B.txt、条件は同じです");
    var changed =
        new ImplementationSpecification(
            1,
            specification.objective(),
            specification.instructions(),
            "B.txt",
            List.of("B.txt"),
            specification.constraints(),
            specification.acceptanceCriteria(),
            specification.references(),
            "REPLACE_EXISTING_TEXT");
    assertEquals("REJECTED", service.prepare(run, changed, null, draft.requestId()).status());
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(delegation);
  }

  /** 外部エージェントへの委譲の操作が、指定された権限・承認条件に従うことを確認する。 */
  @Test
  void unknownRetryStillRequiresExplicitApprovalAfterRequirementsClarification() throws Exception {
    var original = service.prepare(run, specification, null);
    repository.claim(original.requestId(), "dead-jvm");
    repository.finish(original.requestId(), "UNKNOWN", original.requestId(), null);
    var draft = service.prepare(run, null, original.requestId());
    assertEquals("NEEDS_CLARIFICATION", draft.status());
    run.setRunContext(new AgentRunContext("followup", "session", root, "project"));
    run.setUserRequest("はい、対象と指示と条件を確定します");
    var successor = service.prepare(run, specification, null, draft.requestId());
    assertEquals("AWAITING_APPROVAL", successor.status());
    assertEquals(original.requestId(), repository.get(successor.requestId()).previousRequestId());
    assertEquals(
        "AWAITING_APPROVAL",
        service.execute(run, successor.requestId(), 1, successor.specificationSha256()).status());
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(delegation);
  }

  /** 外部エージェントへの委譲で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void clarificationExpiresAndCannotCrossSessionsOrAcceptQuotedConfirmation() {
    var draft = service.prepare(run, null, null);
    run.setUserRequest("`対象と指示を確定します`");
    assertEquals("REJECTED", service.prepare(run, specification, null, draft.requestId()).status());
    run.setUserRequest("はい、要件を確定します");
    run.setRunContext(new AgentRunContext("followup", "other-session", root, "project"));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.prepare(run, specification, null, draft.requestId()));
    run.setRunContext(new AgentRunContext("followup", "session", root, "project"));
    service =
        new ImplementationRequestService(
            repository,
            approvals,
            new ToolPermissionPolicy(
                new ToolPermissionProperties(
                    true, Set.of(ActionCapability.values()), Set.of(), Map.of())),
            properties,
            delegation,
            Clock.offset(Clock.systemUTC(), Duration.ofMinutes(16)));
    assertEquals("REJECTED", service.prepare(run, specification, null, draft.requestId()).status());
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(delegation);
  }

  /** 外部エージェントへの委譲の操作が、指定された権限・承認条件に従うことを確認する。 */
  @Test
  void explicitJapaneseNegationNeverAuthorizesFreshOrClarifiedImplementation() {
    var draft = service.prepare(run, null, null);
    for (String text :
        List.of(
            "対象 A.txt は実装をしないでください",
            "Codex で A.txt を実装をしてはいけません",
            "対象 A.txt は実装するな",
            "いいえ、対象 A.txt の条件は確定しません")) {
      run.setUserRequest(text);
      assertEquals("REJECTED", service.prepare(run, specification, null).status(), text);
      assertEquals(
          "REJECTED", service.prepare(run, specification, null, draft.requestId()).status(), text);
    }
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(delegation);
  }

  /** {@code receipt} の条件を再現するための補助情報を返す。 */
  IsolatedImplementationService.Receipt receipt(String status) {
    return new IsolatedImplementationService.Receipt(
        "receipt",
        "project",
        "session",
        root.toString(),
        "tree",
        "branch",
        "base",
        status,
        "b".repeat(64),
        "commit",
        List.of("A.txt"),
        Map.of(),
        null,
        "diagnostic");
  }

  /** {@code git} の条件を再現するため、検証用の操作や状態を扱う。 */
  void git(String... args) throws Exception {
    var command =
        new ArrayList<String>(
            List.of(
                "git",
                "-c",
                "user.name=Fixture",
                "-c",
                "user.email=fixture@example.invalid",
                "-c",
                "core.hooksPath="));
    command.addAll(List.of(args));
    var p = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).start();
    assertTrue(p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS));
    assertEquals(0, p.exitValue(), new String(p.getInputStream().readAllBytes()));
  }
}
