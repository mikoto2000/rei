package dev.mikoto2000.rei.goal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.ChatExecutionResult;
import dev.mikoto2000.rei.core.policy.ToolPermissionProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 目標段階繰り返しの結果と状態の契約を検証するテスト。 */
@Tag("integration")
class GoalStageLoopTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path dir;

  /** 検証に使う目標一覧。 */
  GoalRepository goals;

  /** 検証に使う段階一覧。 */
  GoalStageRepository stages;

  /** 検証に使うサービス。 */
  GoalStageService service;

  /** 検証に使うファイル一覧。 */
  FileGoalVerifier files = new FileGoalVerifier();

  /** 検証ごとに状態を分離し、対象を操作できる環境を用意する。 */
  GoalRepository.Goal setup() {
    var data = new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("goals.db"));
    goals = new GoalRepository(data, Clock.systemUTC());
    stages = new GoalStageRepository(Clock.systemUTC(), goals);
    service = new GoalStageService(stages, new GoalStageVerifier(files), goals);
    var goal =
        goals.create(
            new AgentRunContext("human", "session", dir, "project"),
            "Produce both",
            List.of(criterion("a"), criterion("b")),
            10,
            20);
    stages.define(goal, 0, List.of(stage("a"), stage("b", "a")));
    return goal;
  }

  /** {@code criterion} の条件を再現するための補助情報を返す。 */
  GoalRepository.FileCriterion criterion(String id) {
    return new GoalRepository.FileCriterion(id + ".json", "", "/ok", "true");
  }

  /** {@code stage} の条件を再現するための補助情報を返す。 */
  GoalStageDefinition stage(String id, String... dependencies) {
    return new GoalStageDefinition(
        id, null, "Produce " + id, List.of(criterion(id)), List.of(dependencies), List.of(), null);
  }

  /** {@code loop} の条件を再現するための補助情報を返す。 */
  GoalLoopService loop(GoalLoopService.Gateway gateway) {
    var loop =
        new GoalLoopService(
            goals,
            files,
            gateway,
            new ToolPermissionProperties(true, null, null, null),
            new GoalEvents(event -> {}, Clock.systemUTC()));
    loop.configureStages(service);
    return loop;
  }

  /** 検証用に、指定された内容を書き込み先へ反映する。 */
  void write(String id, boolean ok) {
    try {
      Files.writeString(dir.resolve(id + ".json"), "{\"ok\":" + ok + "}");
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** {@code done} の条件を再現するための補助情報を返す。 */
  GoalLoopService.Outcome done() {
    return new GoalLoopService.Outcome(ChatExecutionResult.success("DONE", false));
  }

  /** 目標の達成管理で、再送や重複を、受付と実行の規則に従って扱うことを確認する。 */
  @Test
  void runningObservationUsesIndependentConditionsAndKeepsStageCompletionWithHost() {
    var goal = setup();
    var loop =
        loop(
            (claim, run, completed) -> {
              var owner = new AgentRunContext(run, "session", dir, "project");
              var stage = stages.forRun(goal, run);
              assertEquals(
                  GoalStageVerifier.Result.UNSATISFIED,
                  ((GoalStageVerifier.Observation)
                          service.inspectRunning(owner, goal.id()).get("observation"))
                      .result());
              write(stage.stageId(), true);
              var verified = service.inspectRunning(owner, goal.id());
              assertEquals(stage.stageId(), verified.get("stageId"));
              var observation = (GoalStageVerifier.Observation) verified.get("observation");
              assertEquals(GoalStageVerifier.Result.VERIFIED, observation.result());
              assertFalse(observation.artifactVersions().isEmpty());
              assertEquals(
                  "RUNNING",
                  stages.list(goal).stream()
                      .filter(s -> s.definition().id().equals(stage.stageId()))
                      .findFirst()
                      .orElseThrow()
                      .state());
              assertThrows(
                  IllegalStateException.class,
                  () ->
                      service.inspectRunning(
                          new AgentRunContext("other", "session", dir, "project"), goal.id()));
              completed.accept(done());
            });
    assertEquals("COMPLETED", loop.run("project", goal.id()).status());
  }

  @Test
  void onlyVerifiedStageUnlocksNextAndDuplicateCallbackDoesNotAdvance() {
    var goal = setup();
    var calls = new AtomicInteger();
    var loop =
        loop(
            (claim, run, completed) -> {
              assertTrue(goals.reserveLlm(claim));
              var active = stages.forRun(goal, run);
              int call = calls.incrementAndGet();
              assertEquals(call <= 2 ? "a" : "b", active.stageId());
              if (call != 1) write(active.stageId(), true);
              completed.accept(done());
              completed.accept(done());
            });
    assertEquals("COMPLETED", loop.run("project", goal.id()).status());
    assertEquals(3, calls.get());
    assertTrue(stages.list(goal).stream().allMatch(s -> s.state().equals("VERIFIED")));
    assertEquals(3, stages.attempts(goal).size());
  }

  /** 目標の達成管理で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void laterDamageInvalidatesEarlierEvidenceAndGoalCannotComplete() {
    var goal = setup();
    var calls = new AtomicInteger();
    var loop =
        loop(
            (claim, run, completed) -> {
              calls.incrementAndGet();
              var active = stages.forRun(goal, run);
              write(active.stageId(), true);
              if (active.stageId().equals("b")) write("a", false);
              completed.accept(done());
            });
    assertEquals("BLOCKED", loop.run("project", goal.id()).status());
    assertFalse(files.verify(goals.get("project", goal.id())).satisfied());
    assertTrue(calls.get() <= 10);
    assertEquals(2, stages.evidence(goal).size());
    assertEquals("STALE", stages.list(goal).getFirst().state());
  }

  /** プロセス再開について、明示的な状態の照合を必要とすることを確認する。 */
  @Test
  void processRestartRequiresExplicitReconcileAndDoesNotRepeatVerifiedStage() {
    var goal = setup();
    var callback = new AtomicReference<Consumer<GoalLoopService.Outcome>>();
    var loop =
        loop(
            new GoalLoopService.Gateway() {
              /** {@code dispatch} の条件を再現するため、検証用の操作や状態を扱う。 */
              public void dispatch(
                  GoalRepository.Claim claim,
                  String run,
                  Consumer<GoalLoopService.Outcome> completed) {
                var active = stages.forRun(goal, run);
                if (active.stageId().equals("a")) {
                  write("a", true);
                  completed.accept(done());
                } else callback.set(completed);
              }

              /** 検証用に、目標の達成管理において、入力先Flightかどうかを判定する。 */
              public boolean isInFlight(GoalRepository.Goal ignored) {
                return false;
              }
            });
    var running = loop.run("project", goal.id());
    assertEquals("RUNNING", running.status());
    var data = new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("goals.db"));
    var reopened = new GoalStageRepository(Clock.systemUTC(), goals);
    assertEquals("VERIFIED", reopened.list(goal).getFirst().state());
    assertThrows(IllegalStateException.class, () -> loop.run("project", goal.id()));
    loop.reconcile("project", goal.id(), running.currentRunId());
    write("b", true);
    callback.get().accept(done());
    assertEquals("PAUSED", goals.get("project", goal.id()).status());
    var resumed =
        loop(
            (claim, run, completed) -> {
              assertEquals("b", stages.forRun(goal, run).stageId());
              completed.accept(done());
            });
    assertEquals("COMPLETED", resumed.run("project", goal.id()).status());
    assertEquals(1, stages.attempts(goal).stream().filter(a -> a.stageId().equals("a")).count());
  }

  /** 目標の達成管理で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void parentAndFinalGoalEachNeedTheirOwnIndependentVerification() {
    var goal = setup();
    var loop =
        loop(
            (claim, run, completed) -> {
              var active = stages.forRun(goal, run);
              write(active.stageId(), true);
              completed.accept(done());
            });
    // Satisfying both stages cannot substitute a different mandatory final condition.
    var other =
        goals.create(
            new AgentRunContext("h", "session", dir, "project"),
            "Final independent",
            List.of(criterion("final")),
            3,
            10);
    stages.define(other, 0, List.of(stage("a")));
    var calls = new AtomicInteger();
    var finalLoop =
        loop(
            (claim, run, completed) -> {
              calls.incrementAndGet();
              write("a", true);
              completed.accept(done());
            });
    assertEquals("BLOCKED", finalLoop.run("project", other.id()).status());
    assertFalse(files.verify(goals.get("project", other.id())).satisfied());
    assertEquals("VERIFIED", stages.list(other).getFirst().state());
  }
}
