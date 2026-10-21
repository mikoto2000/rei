package dev.mikoto2000.rei.goal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.ChatExecutionResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 目標修復の結果と状態の契約を検証するテスト。 */
class GoalRepairTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** 検証に使う目標一覧。 */
  GoalRepository goals;

  /** 検証に使う確認の窓口。 */
  FileGoalVerifier verifier;

  /** {@code sha} の条件を再現するための補助情報を返す。 */
  String sha(String value) throws Exception {
    return HexFormat.of()
        .formatHex(
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
  }

  /** 各検証を独立した状態で始められるよう、依存先と検証データを準備する。 */
  @BeforeEach
  void setup() {
    goals =
        new GoalRepository(
            new DriverManagerDataSource("jdbc:sqlite:" + root.resolve("goals.db")),
            Clock.systemUTC());
    verifier = new FileGoalVerifier();
    verifier.setCompletionGate(
        new GoalCompletionGate(
            goals,
            (o, r) -> {
              throw new java.io.IOException();
            },
            (o, r) -> {
              throw new java.io.IOException();
            },
            (r, d) -> {
              throw new java.io.IOException();
            },
            Clock.systemUTC(),
            false));
  }

  /** 指定された条件を再現するための検証対象や入力情報を作成する。 */
  GoalRepository.Goal create(int calls) throws Exception {
    var criterion = new GoalRepository.FileCriterion("out.txt", sha("correct"));
    return goals.create(
        new AgentRunContext("human", "session", root, "project"),
        "repair required result",
        List.of(criterion),
        new GoalCompletionGate.Definition(List.of(criterion), null, List.of(), List.of(), null),
        3,
        calls);
  }

  /** {@code loop} の条件を再現するための補助情報を返す。 */
  GoalLoopService loop(GoalLoopService.Gateway gateway) {
    return new GoalLoopService(
        goals,
        verifier,
        gateway,
        new dev.mikoto2000.rei.core.policy.ToolPermissionProperties(true, null, null, null),
        new GoalEvents(e -> {}, Clock.systemUTC()));
  }

  /** 目標の達成管理の操作が、指定された権限・承認条件に従うことを確認する。 */
  @Test
  void eightFailureClassesKeepUnknownAndApprovalOutOfAutomaticRepair() {
    var expected =
        Map.of(
            "completion_tests_failed",
            GoalRepairDiagnosis.Kind.TEST_FAILURE,
            "digest_mismatch",
            GoalRepairDiagnosis.Kind.IMPLEMENTATION_INCONSISTENCY,
            "completion_requirement_unmet",
            GoalRepairDiagnosis.Kind.REQUIREMENT_UNMET,
            "external_transient",
            GoalRepairDiagnosis.Kind.TRANSIENT_EXTERNAL,
            "permission_required",
            GoalRepairDiagnosis.Kind.APPROVAL_WAIT,
            "execution_failed",
            GoalRepairDiagnosis.Kind.UNKNOWN_RESULT,
            "symbolic_link_rejected",
            GoalRepairDiagnosis.Kind.UNREPAIRABLE,
            "budget_exhausted",
            GoalRepairDiagnosis.Kind.BUDGET_INSUFFICIENT);
    expected.forEach((reason, kind) -> assertEquals(kind, GoalRepairDiagnosis.of(reason).kind()));
    assertTrue(GoalRepairDiagnosis.of("completion_tests_failed").repairable());
    for (String reason :
        List.of(
            "execution_failed",
            "token_usage_unknown",
            "permission_required",
            "policy_denied",
            "new_unknown_reason",
            "external_transient")) assertFalse(GoalRepairDiagnosis.of(reason).repairable());
  }

  /** 目標の達成管理で、保存した情報を、再起動や復元後の処理へ正しく引き継ぐことを確認する。 */
  @Test
  void initialCallSliceLeavesDurableCallsForRepairWithoutReplenishingTokens() throws Exception {
    var goal = create(4);
    var claim = goals.claim("project", goal.id());
    String run = goals.beginAttempt(claim);
    var reservation = new GoalRepairBudget(goals.modelBudget(claim, run), 2);
    assertTrue(reservation.tryReserve());
    assertTrue(reservation.tryReserve());
    assertFalse(reservation.tryReserve());
    assertTrue(reservation.boundaryReached());
    assertEquals(2, goals.remainingLlm(claim));
    assertEquals(2, goals.get("project", goal.id()).llmCallsUsed());
    var repair = goals.modelBudget(claim, run);
    assertTrue(repair.tryReserve());
    assertTrue(repair.tryReserve());
    assertFalse(repair.tryReserve());
    assertEquals(4, goals.get("project", goal.id()).llmCallsUsed());
  }

  /** 目標の達成管理で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void knownReserveBoundaryVerifiesThenRepairsWithinExistingGoalLimits() throws Exception {
    var goal = create(4);
    var calls = new AtomicInteger();
    var loop =
        loop(
            (claim, run, done) -> {
              assertTrue(goals.reserveLlm(claim));
              int n = calls.incrementAndGet();
              try {
                Files.writeString(root.resolve("out.txt"), n == 1 ? "wrong" : "correct");
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
              done.accept(
                  n == 1
                      ? new GoalLoopService.Outcome(
                          ChatExecutionResult.failed("bounded", "llm_call_budget_exceeded"),
                          "repair_reserve_reached")
                      : new GoalLoopService.Outcome(ChatExecutionResult.success("done", false)));
            });
    assertEquals("COMPLETED", loop.run("project", goal.id()).status());
    assertEquals(2, calls.get());
    assertEquals(2, goals.get("project", goal.id()).llmCallsUsed());
  }

  /** 目標の達成管理で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void controlledBudgetEndCanCompleteOnlyWithIndependentGateEvidence() throws Exception {
    var goal = create(1);
    var loop =
        loop(
            (claim, run, done) -> {
              assertTrue(goals.reserveLlm(claim));
              try {
                Files.writeString(root.resolve("out.txt"), "correct");
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
              done.accept(
                  new GoalLoopService.Outcome(
                      ChatExecutionResult.failed("bounded", "llm_call_budget_exceeded"),
                      "llm_call_budget_exceeded"));
            });
    assertEquals("COMPLETED", loop.run("project", goal.id()).status());
    assertEquals(1, goals.get("project", goal.id()).llmCallsUsed());
  }

  /** 目標の達成管理の操作が、指定された権限・承認条件に従うことを確認する。 */
  @Test
  void unknownResultAndPermissionNeverReplayEvenWhenCallsRemain() throws Exception {
    for (String reason : List.of("execution_failed", "permission_required", "external_transient")) {
      var goal = create(4);
      var calls = new AtomicInteger();
      var loop =
          loop(
              (claim, run, done) -> {
                assertTrue(goals.reserveLlm(claim));
                calls.incrementAndGet();
                done.accept(
                    new GoalLoopService.Outcome(ChatExecutionResult.failed("stopped"), reason));
              });
      assertEquals(
          reason.equals("permission_required") ? "WAITING_APPROVAL" : "FAILED",
          loop.run("project", goal.id()).status());
      assertEquals(1, calls.get());
    }
  }

  /** 目標の達成管理で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void pendingTokenUsageCannotBeHiddenByMatchingFiles() throws Exception {
    var properties = new dev.mikoto2000.rei.llm.LlmProperties();
    properties.getOutputLimit().setMaxTotalTokensPerGoal(20);
    goals =
        new GoalRepository(
            new DriverManagerDataSource("jdbc:sqlite:" + root.resolve("goals.db")),
            Clock.systemUTC(),
            properties);
    var goal = create(4);
    var loop =
        loop(
            (claim, run, done) -> {
              assertTrue(goals.reserveLlm(claim));
              try {
                Files.writeString(root.resolve("out.txt"), "correct");
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
              done.accept(new GoalLoopService.Outcome(ChatExecutionResult.success("done", false)));
            });
    assertEquals("BLOCKED", loop.run("project", goal.id()).status());
    assertEquals("token_usage_unknown", goals.get("project", goal.id()).reason());
  }

  /** 目標の達成管理で、条件が変わっても、引き継ぐべき情報や状態を保持することを確認する。 */
  @Test
  void progressKeepsUnknownExecutionSeparateFromRepairableFileMismatch() throws Exception {
    var goal = create(4);
    var loop =
        loop(
            (claim, run, done) ->
                done.accept(
                    new GoalLoopService.Outcome(
                        ChatExecutionResult.failed("unknown"), "execution_failed")));
    loop.run("project", goal.id());
    var progress = loop.progress("project", goal.id());
    assertEquals(GoalRepairDiagnosis.Kind.UNKNOWN_RESULT, progress.diagnosis().kind());
    assertFalse(progress.diagnosis().repairable());
    var old =
        new GoalCompletionProgress(
            "g",
            "r",
            "BLOCKED",
            new FileGoalVerifier.Verification(false, "digest_mismatch"),
            List.of(),
            false);
    assertEquals(GoalRepairDiagnosis.Kind.IMPLEMENTATION_INCONSISTENCY, old.diagnosis().kind());
  }

  /** 目標の達成管理の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void cancellationAndUnknownResultDoNotBecomeReserveBoundaryRepair() throws Exception {
    var goal = create(4);
    var calls = new AtomicInteger();
    var loop =
        loop(
            (claim, run, done) -> {
              var slice = new GoalRepairBudget(goals.modelBudget(claim, run), 2);
              assertTrue(slice.tryReserve());
              assertTrue(slice.tryReserve());
              assertTrue(slice.boundaryReached());
              calls.incrementAndGet();
              done.accept(new GoalLoopService.Outcome(ChatExecutionResult.failed("unknown")));
            });
    assertEquals("FAILED", loop.run("project", goal.id()).status());
    assertEquals(1, calls.get());
    assertEquals(2, goals.get("project", goal.id()).llmCallsUsed());
    var cancelled = create(4);
    var cancelLoop =
        loop(
            (claim, run, done) ->
                done.accept(
                    new GoalLoopService.Outcome(
                        ChatExecutionResult.cancelled(), "repair_reserve_reached")));
    assertEquals("PAUSED", cancelLoop.run("project", cancelled.id()).status());
    assertEquals("run_cancelled", goals.get("project", cancelled.id()).reason());
  }

  /** 目標の達成管理で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void reserveForwardsActualTokenUsageAndNeverHidesUnknown() throws Exception {
    var properties = new dev.mikoto2000.rei.llm.LlmProperties();
    properties.getOutputLimit().setMaxTotalTokensPerGoal(20);
    goals =
        new GoalRepository(
            new DriverManagerDataSource("jdbc:sqlite:" + root.resolve("goals.db")),
            Clock.systemUTC(),
            properties);
    var goal = create(4);
    var claim = goals.claim("project", goal.id());
    String run = goals.beginAttempt(claim);
    var slice = new GoalRepairBudget(goals.modelBudget(claim, run), 2);
    assertTrue(slice.tryReserve());
    slice.recordTotalTokens(7);
    assertEquals(7, goals.get("project", goal.id()).totalTokens());
    assertTrue(slice.tryReserve());
    assertThrows(
        dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException.class,
        () -> slice.recordTotalTokens(null));
    assertTrue(slice.usageUnknown());
    assertFalse(slice.boundaryReached());
    assertFalse(slice.tryReserve());
    assertEquals(7, goals.get("project", goal.id()).totalTokens());
    assertTrue(goals.get("project", goal.id()).tokenUsageUnknown());
  }
}
