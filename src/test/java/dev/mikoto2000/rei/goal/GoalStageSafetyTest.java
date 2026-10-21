package dev.mikoto2000.rei.goal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.ChatExecutionResult;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 目標段階安全性の結果と状態の契約を検証するテスト。 */
class GoalStageSafetyTest extends GoalStageLoopTest {
  /** 目標の達成管理の操作が、指定された権限・承認条件に従うことを確認する。 */
  @Test
  void evidenceAttachmentCannotAuthorizeReplayOfUnknownSideEffect() {
    var goal = setup();
    loop((claim, run, complete) ->
            complete.accept(
                new GoalLoopService.Outcome(
                    ChatExecutionResult.failed("unknown"), "external_result_unknown")))
        .run("project", goal.id());
    var stage = stages.list(goal).getFirst();
    assertThrows(
        IllegalStateException.class,
        () ->
            stages.attachProof(
                goals.get("project", goal.id()),
                "a",
                stage.version(),
                new GoalCompletionGate.Proof(null, List.of())));
    assertEquals("BLOCKED", stages.list(goal).getFirst().state());
  }

  /** 不明上位の実行を受け付けないことを確認する。 */
  @Test
  void rejectsUnknownAncestorBeforeWalkingParentChain() {
    var goal = setup();
    var child =
        new GoalStageDefinition(
            "x", "y", "Child", List.of(criterion("x")), List.of(), List.of(), null);
    var parent =
        new GoalStageDefinition(
            "y", "missing", "Parent", List.of(criterion("y")), List.of(), List.of(), null);
    assertThrows(
        IllegalArgumentException.class,
        () -> GoalStagePlanValidator.validate(goal, List.of(child, parent)));
  }

  /** 目標の達成管理で、保存した情報を、再起動や復元後の処理へ正しく引き継ぐことを確認する。 */
  @Test
  void restartAfterVerificationCommitSkipsConfirmedStage() {
    var goal = setup();
    var claim = goals.claim("project", goal.id());
    var run = goals.beginAttempt(claim);
    var attempt = stages.begin(claim, run, stages.list(goal).getFirst());
    write("a", true);
    var observation =
        new GoalStageVerifier(files).verify(goal, stages.list(goal).getFirst(), stages.list(goal));
    goals.atomic(
        () -> {
          assertTrue(stages.finish(claim, attempt, observation));
          goals.recordAttempt(claim, run, "VERIFIED", "criteria_verified");
          return null;
        });
    var loop =
        loop(
            new GoalLoopService.Gateway() {
              /** {@code dispatch} の条件を再現するため、検証用の操作や状態を扱う。 */
              public void dispatch(
                  GoalRepository.Claim ignored,
                  String next,
                  java.util.function.Consumer<GoalLoopService.Outcome> complete) {
                assertEquals("b", stages.forRun(goal, next).stageId());
                write("b", true);
                complete.accept(done());
              }

              /** 検証用に、目標の達成管理において、入力先Flightかどうかを判定する。 */
              public boolean isInFlight(GoalRepository.Goal ignored) {
                return false;
              }
            });
    loop.reconcile("project", goal.id(), run);
    assertEquals("VERIFIED", stages.list(goal).getFirst().state());
    assertEquals("COMPLETED", loop.run("project", goal.id()).status());
    assertEquals(1, stages.attempts(goal).stream().filter(a -> a.stageId().equals("a")).count());
  }

  /** 目標の達成管理で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void stagesCannotExceedWholeGoalTokenLimit() {
    setup();
    var properties = new dev.mikoto2000.rei.llm.LlmProperties();
    properties.getOutputLimit().setMaxTotalTokensPerGoal(10);
    var data =
        new org.springframework.jdbc.datasource.DriverManagerDataSource(
            "jdbc:sqlite:" + dir.resolve("limited.db"));
    goals = new GoalRepository(data, java.time.Clock.systemUTC(), properties);
    stages = new GoalStageRepository(java.time.Clock.systemUTC(), goals);
    service = new GoalStageService(stages, new GoalStageVerifier(files), goals);
    var goal =
        goals.create(
            new AgentRunContext("human", "session", dir, "project"),
            "Limited",
            List.of(criterion("a"), criterion("b")),
            10,
            20);
    stages.define(goal, 0, List.of(stage("a"), stage("b", "a")));
    var result =
        loop((claim, run, complete) -> {
              assertTrue(goals.reserveLlm(claim, run));
              goals.recordTotalTokens(claim, run, 10);
              write("a", true);
              complete.accept(done());
            })
            .run("project", goal.id());
    assertEquals("BLOCKED", result.status());
    assertEquals(1, result.attempts());
    assertEquals(10, result.totalTokens());
    assertEquals("TODO", stages.list(goal).get(1).state());
  }

  /** 目標の達成管理で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void changedEvidenceReopensCompletedStagedGoalWithoutResettingBudget() {
    var goal = setup();
    var loop =
        loop(
            (claim, run, complete) -> {
              write(stages.forRun(goal, run).stageId(), true);
              complete.accept(done());
            });
    assertEquals("COMPLETED", loop.run("project", goal.id()).status());
    try {
      java.nio.file.Files.writeString(dir.resolve("a.json"), "{ \"ok\": true }");
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    assertEquals("BLOCKED", loop.progress("project", goal.id()).state());
    assertEquals("BLOCKED", goals.get("project", goal.id()).status());
    assertEquals("STALE", stages.list(goal).getFirst().state());
    assertEquals("COMPLETED", loop.run("project", goal.id()).status());
    assertEquals(10, goals.get("project", goal.id()).maxRuns());
  }

  /** 目標の達成管理で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void repairedPredecessorAllowsIndependentRecheckOfKnownFinishedSuccessor() {
    var goal = setup();
    var first =
        loop(
            (claim, run, complete) -> {
              String id = stages.forRun(goal, run).stageId();
              write(id, true);
              if (id.equals("b")) write("a", false);
              complete.accept(done());
            });
    assertEquals("BLOCKED", first.run("project", goal.id()).status());
    var repaired =
        loop(
            (claim, run, complete) -> {
              if (stages.forRun(goal, run).stageId().equals("a")) write("a", true);
              complete.accept(done());
            });
    assertEquals("COMPLETED", repaired.run("project", goal.id()).status());
  }

  /** 目標の達成管理の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void cancellingOwningGoalClosesStageAttemptAtomically() {
    var goal = setup();
    var loop = loop((claim, run, complete) -> {});
    assertEquals("RUNNING", loop.run("project", goal.id()).status());
    loop.cancel("project", goal.id());
    assertEquals("BLOCKED", stages.list(goal).getFirst().state());
    assertEquals("CANCELLED", stages.attempts(goal).getFirst().status());
  }

  /** 目標の達成管理で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void independentConditionCountsMeasureRepairImprovement() {
    var goal = setup();
    var definition =
        new GoalStageDefinition(
            "combined",
            null,
            "Both outputs",
            List.of(criterion("a"), criterion("b")),
            List.of(),
            List.of(),
            null);
    var snapshot = new GoalStageRepository.Stage(definition, 1, 1, "RUNNING", "", null, null);
    var verifier = new GoalStageVerifier(files);
    write("a", true);
    var before = verifier.verify(goal, snapshot, List.of());
    assertEquals(1, before.satisfiedConditions());
    assertEquals(2, before.totalConditions());
    write("b", true);
    var after = verifier.verify(goal, snapshot, List.of());
    assertEquals(2, after.satisfiedConditions());
    assertEquals(GoalStageVerifier.Result.VERIFIED, after.result());
  }

  /** 目標の達成管理の操作が、指定された権限・承認条件に従うことを確認する。 */
  @Test
  void splitCapabilityCanBeDisabledForRepairOnlyEvaluation() {
    var goal = setup();
    service.configureSplit(false);
    loop((claim, run, complete) -> {
          if (stages.forRun(goal, run).number() == 2)
            assertThrows(
                IllegalStateException.class,
                () ->
                    service.proposeSplit(
                        new AgentRunContext(run, "session", dir, "project"),
                        goal.id(),
                        1,
                        List.of(
                            new GoalStageDefinition(
                                "child",
                                "a",
                                "Child",
                                List.of(criterion("child")),
                                List.of(),
                                List.of(),
                                null))));
          complete.accept(done());
        })
        .run("project", goal.id());
    assertEquals(1, stages.planVersion(goal));
  }

  /** 目標の達成管理で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void finalGoalRepairCannotBypassReverificationOfPreviouslyVerifiedStages() {
    setup();
    var goal =
        goals.create(
            new AgentRunContext("human", "session", dir, "project"),
            "Final artifact",
            List.of(criterion("final")),
            5,
            10);
    stages.define(goal, 0, List.of(stage("a")));
    var result =
        loop((claim, run, complete) -> {
              if (stages.forRun(goal, run) != null) write("a", true);
              else {
                write("final", true);
                write("a", false);
              }
              complete.accept(done());
            })
            .run("project", goal.id());
    assertEquals("BLOCKED", result.status());
    assertEquals("STALE", stages.list(goal).getFirst().state());
    assertTrue(files.verify(result).satisfied());
  }

  /** 目標完了について、確認済み定義状態の記録を維持することを確認する。 */
  @Test
  void goalCompletionKeepsTheVerifiedDefinitionSnapshotAcrossConcurrentChanges() {
    setup();
    write("a", true);
    var owner = new AgentRunContext("human", "session", dir, "project");
    var goal = goals.create(owner, "Snapshot", List.of(criterion("a")), 5, 10);
    var stronger =
        new GoalCompletionGate.Definition(
            List.of(new GoalRepository.FileCriterion("proof.txt", "b".repeat(64))),
            null,
            List.of(),
            List.of(),
            null);
    files =
        new FileGoalVerifier() {
          /** 検証用に、要求された条件を満たすことを確認する。 */
          @Override
          public Verification verify(GoalRepository.Goal snapshot) {
            goals.defineCompletion(owner, snapshot.id(), stronger);
            return new Verification(true, "criteria_verified");
          }
        };
    var loop = loop((claim, run, complete) -> {});
    assertThrows(IllegalStateException.class, () -> loop.verify("project", goal.id()));
    assertEquals("READY", goals.get("project", goal.id()).status());
  }

  /** 目標の達成管理の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void reconciliationPreservesReservedUsageOfInterruptedStageAttempt() {
    var goal = setup();
    var claim = goals.claim("project", goal.id());
    var run = goals.beginAttempt(claim);
    stages.begin(claim, run, stages.list(goal).getFirst());
    assertTrue(goals.reserveLlm(claim, run));
    assertTrue(goals.reserveLlm(claim, run));
    var loop =
        loop(
            new GoalLoopService.Gateway() {
              /** {@code dispatch} の条件を再現するため、検証用の操作や状態を扱う。 */
              public void dispatch(
                  GoalRepository.Claim ignored,
                  String next,
                  java.util.function.Consumer<GoalLoopService.Outcome> complete) {}

              /** 検証用に、目標の達成管理において、入力先Flightかどうかを判定する。 */
              public boolean isInFlight(GoalRepository.Goal ignored) {
                return false;
              }
            });
    loop.reconcile("project", goal.id(), run);
    assertEquals(2, stages.attempts(goal).getFirst().callsUsed());
    assertEquals(2, goals.get("project", goal.id()).llmCallsUsed());
  }
}
