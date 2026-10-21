package dev.mikoto2000.rei.goal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 目標段階保存と検索の窓口の結果と状態の契約を検証するテスト。 */
@Tag("integration")
class GoalStageRepositoryTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path dir;

  /** 検証で使用する取得元を、指定された条件に合わせて用意する。 */
  DriverManagerDataSource source() {
    return new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("goals.db"));
  }

  /** {@code goals} の条件を再現するための補助情報を返す。 */
  GoalRepository goals() {
    return new GoalRepository(source(), Clock.systemUTC());
  }

  /** {@code stage} の条件を再現するための補助情報を返す。 */
  GoalStageDefinition stage(String id, String... dependencies) {
    return new GoalStageDefinition(
        id,
        null,
        "Produce " + id,
        List.of(new GoalRepository.FileCriterion(id + ".json", "", "/ok", "true")),
        List.of(dependencies),
        List.of(),
        null);
  }

  /** {@code goal} の条件を再現するための補助情報を返す。 */
  GoalRepository.Goal goal(GoalRepository goals) {
    return goals.create(
        new AgentRunContext("human", "session", dir, "project"),
        "All outputs",
        List.of(new GoalRepository.FileCriterion("final.json", "", "/ok", "true")),
        10,
        20);
  }

  /** 目標の達成管理で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void rejectsCyclesMissingReferencesAndConditionWeakening() {
    var goals = goals();
    var goal = goal(goals);
    var stages = new GoalStageRepository(Clock.systemUTC(), goals);
    assertThrows(
        IllegalArgumentException.class,
        () -> stages.define(goal, 0, List.of(stage("a", "missing"))));
    assertThrows(
        IllegalArgumentException.class,
        () -> stages.define(goal, 0, List.of(stage("a", "b"), stage("b", "a"))));
    stages.define(goal, 0, List.of(stage("a"), stage("b", "a")));
    assertThrows(IllegalStateException.class, () -> stages.define(goal, 0, List.of(stage("a"))));
    assertThrows(IllegalArgumentException.class, () -> stages.define(goal, 1, List.of(stage("a"))));
    assertEquals(2, stages.list(goal).size());
  }

  /** 重複の有無を受け付けないことを確認する。 */
  @Test
  void persistsIndependentEvidenceAndRejectsDuplicateOrOutdatedCompletion() throws Exception {
    var goals = goals();
    var goal = goal(goals);
    var stages = new GoalStageRepository(Clock.systemUTC(), goals);
    stages.define(goal, 0, List.of(stage("a"), stage("b", "a")));
    var claim = goals.claim("project", goal.id());
    String run = goals.beginAttempt(claim);
    var attempt = stages.begin(claim, run, stages.list(goal).getFirst());
    Files.writeString(dir.resolve("a.json"), "{\"ok\":true}");
    var verifier = new GoalStageVerifier(new FileGoalVerifier());
    var result = verifier.verify(goal, stages.list(goal).getFirst(), stages.list(goal));
    assertEquals(GoalStageVerifier.Result.VERIFIED, result.result());
    assertTrue(stages.finish(claim, attempt, result));
    assertFalse(stages.finish(claim, attempt, result));
    var reopened = new GoalStageRepository(Clock.systemUTC(), goals);
    assertEquals("VERIFIED", reopened.list(goal).getFirst().state());
    assertEquals(1, reopened.attempts(goal).size());
    assertEquals(attempt.id(), reopened.list(goal).getFirst().evidence().attemptId());
    assertThrows(
        IllegalStateException.class,
        () -> reopened.begin(claim, run, stages.list(goal).getFirst()));
    Files.writeString(dir.resolve("a.json"), "{\"ok\":false}");
    assertEquals(
        GoalStageVerifier.Result.UNSATISFIED,
        verifier.verify(goal, reopened.list(goal).getFirst(), reopened.list(goal)).result());
  }

  /** 目標の達成管理で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void largeBoundedVerificationContractsRemainReadableAfterEvidenceCommit() throws Exception {
    var goals = goals();
    var goal = goal(goals);
    var stages = new GoalStageRepository(Clock.systemUTC(), goals);
    var files = new FileGoalVerifier();
    var document = new java.util.LinkedHashMap<String, String>();
    var criteria = new java.util.ArrayList<GoalRepository.FileCriterion>();
    var requirements = new java.util.ArrayList<GoalCompletionGate.Requirement>();
    String value = "value".repeat(200);
    for (int i = 0; i < 16; i++) {
      document.put("v" + i, value);
      var criterion =
          new GoalRepository.FileCriterion(
              "large.json", "", "/v" + i, GoalStageRepository.encode(value));
      criteria.add(criterion);
      requirements.add(
          new GoalCompletionGate.Requirement(
              "r" + i, "Verify the persisted artifact. ".repeat(30), true, criterion));
    }
    Files.writeString(dir.resolve("large.json"), GoalStageRepository.encode(document));
    Files.writeString(dir.resolve("proof.txt"), "proof");
    var definition =
        new GoalStageDefinition(
            "large",
            null,
            "Verify all fixed conditions",
            List.of(criteria.getFirst()),
            List.of(),
            List.of(),
            new GoalCompletionGate.Definition(
                List.of(
                    new GoalRepository.FileCriterion(
                        "proof.txt", files.fingerprint(dir, "proof.txt").sha256())),
                null,
                List.of(),
                criteria,
                null,
                requirements));
    assertTrue(GoalStageRepository.encode(definition).length() > 32768);
    stages.define(goal, 0, List.of(definition));
    var claim = goals.claim("project", goal.id());
    var run = goals.beginAttempt(claim);
    var attempt = stages.begin(claim, run, stages.list(goal).getFirst());
    var verifier = new GoalStageVerifier(files);
    verifier.configureGate(
        new GoalCompletionGate(
            goals,
            (owner, reference) -> {
              throw new AssertionError("No review expected");
            },
            (owner, reference) -> {
              throw new AssertionError("No artifact expected");
            },
            (root, deadline) -> {
              throw new AssertionError("No patch capture expected");
            },
            Clock.systemUTC(),
            false));
    var observed = verifier.verify(goal, stages.list(goal).getFirst(), stages.list(goal));
    assertEquals(GoalStageVerifier.Result.VERIFIED, observed.result());
    assertTrue(stages.finish(claim, attempt, observed));
    var reopened = new GoalStageRepository(Clock.systemUTC(), goals);
    assertEquals("VERIFIED", reopened.list(goal).getFirst().state());
    assertEquals(1, reopened.evidence(goal).size());
  }

  /** 確認済み目標確定について、未確認段階計画を受け付けないことを確認する。 */
  @Test
  void verifiedGoalCommitRejectsAnUnverifiedStagePlan() throws Exception {
    var goals = goals();
    var goal = goal(goals);
    Files.writeString(dir.resolve("final.json"), "{\"ok\":true}");
    var stages = new GoalStageRepository(Clock.systemUTC(), goals);
    stages.define(goal, 0, List.of(stage("a")));
    assertTrue(new FileGoalVerifier().verify(goal).satisfied());
    assertThrows(
        IllegalStateException.class, () -> goals.verifiedWithoutRun(goal, "criteria_verified"));
    assertEquals("READY", goals.get("project", goal.id()).status());
  }

  /** 完了について、解放する利用権の確保形式実行を妨げる条件ありを停止することを確認する。 */
  @Test
  void completionStopReleasesClaimAsBlockedWhenStagesAreNotVerified() throws Exception {
    var goals = goals();
    var goal = goal(goals);
    Files.writeString(dir.resolve("final.json"), "{\"ok\":true}");
    var stages = new GoalStageRepository(Clock.systemUTC(), goals);
    stages.define(goal, 0, List.of(stage("a")));
    var claim = goals.claim("project", goal.id());
    var stopped = goals.stop(claim, "COMPLETED", "criteria_verified");
    assertEquals("BLOCKED", stopped.status());
    assertEquals("stage_verification_required", stopped.reason());
    assertFalse(goals.active(claim));
  }

  /** 目標の達成管理で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void fileRevalidationDoesNotBlockAnotherGoalsBudgetReservation() throws Exception {
    var data = source();
    var properties = new java.util.Properties();
    properties.setProperty("busy_timeout", "200");
    data.setConnectionProperties(properties);
    var goals = new GoalRepository(data, Clock.systemUTC());
    var goal = goal(goals);
    var stages = new GoalStageRepository(Clock.systemUTC(), goals);
    stages.define(goal, 0, List.of(stage("a"), stage("b", "a")));
    var first = goals.claim("project", goal.id());
    var run = goals.beginAttempt(first);
    var attempt = stages.begin(first, run, stages.list(goal).getFirst());
    Files.writeString(dir.resolve("a.json"), "{\"ok\":true}");
    var files = new FileGoalVerifier();
    var observed =
        new GoalStageVerifier(files).verify(goal, stages.list(goal).getFirst(), stages.list(goal));
    assertTrue(stages.finish(first, attempt, observed));
    goals.recordAttempt(first, run, "VERIFIED", "criteria_verified");
    goals.stop(first, "PAUSED", "pause");
    var other = goal(goals);
    var otherClaim = goals.claim("project", other.id());
    var otherRun = goals.beginAttempt(otherClaim);
    var entered = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    var slowFiles =
        new FileGoalVerifier() {
          /** 検証用に、要求された条件を満たすことを確認する。 */
          @Override
          public Verification verify(Path root, GoalRepository.FileCriterion criterion) {
            entered.countDown();
            try {
              if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                throw new IllegalStateException("Observation release timed out");
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              throw new IllegalStateException(e);
            }
            return super.verify(root, criterion);
          }
        };
    var service = new GoalStageService(stages, new GoalStageVerifier(slowFiles), goals);
    try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var refresh = workers.submit(() -> service.refresh(goal));
      try {
        assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS));
        var reservation = workers.submit(() -> goals.reserveLlm(otherClaim, otherRun));
        assertTrue(reservation.get(3, java.util.concurrent.TimeUnit.SECONDS));
      } finally {
        release.countDown();
      }
      refresh.get(5, java.util.concurrent.TimeUnit.SECONDS);
    }
    assertEquals(1, goals.get("project", other.id()).llmCallsUsed());
  }
}
