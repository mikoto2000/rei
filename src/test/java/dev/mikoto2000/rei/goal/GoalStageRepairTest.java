package dev.mikoto2000.rei.goal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import dev.mikoto2000.rei.core.chat.ChatExecutionResult;
import org.junit.jupiter.api.Test;

/** 目標段階修復の結果と状態の契約を検証するテスト。 */
class GoalStageRepairTest extends GoalStageLoopTest {
  /** 変更なし根拠について、修復を停止することを確認する。 */
  @Test
  void unchangedEvidenceStopsRepairWithoutConsumingAllRuns() {
    var goal = setup();
    var loop =
        loop(
            (claim, run, done) -> {
              goals.reserveLlm(claim);
              done.accept(done());
            });
    var result = loop.run("project", goal.id());
    assertEquals("BLOCKED", result.status());
    assertEquals("stage_repair_stalled", result.reason());
    assertEquals(2, result.attempts());
    assertEquals(2, stages.attempts(goal).size());
  }

  /** 予算を維持することを確認する。 */
  @Test
  void changingButUnsuccessfulRepairHasStageLimitAndKeepsBudget() {
    var goal = setup();
    service.configureRepair(2);
    var loop =
        loop(
            (claim, run, complete) -> {
              try {
                java.nio.file.Files.writeString(
                    dir.resolve("a.json"),
                    "{\"ok\":false,\"attempt\":"
                        + goals.get("project", goal.id()).attempts()
                        + "}");
              } catch (Exception e) {
                throw new IllegalStateException(e);
              }
              complete.accept(done());
            });
    var result = loop.run("project", goal.id());
    assertEquals("stage_repair_limit", result.reason());
    assertEquals(3, result.attempts());
    assertEquals(10, result.maxRuns());
  }

  /** 目標の達成管理で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void noRepairModeStopsAtFirstFailedIndependentVerification() {
    var goal = setup();
    service.configureRepair(0);
    var result = loop((claim, run, done) -> done.accept(done())).run("project", goal.id());
    assertEquals(1, result.attempts());
    assertEquals("BLOCKED", result.status());
  }

  /** 目標の達成管理の操作が、指定された権限・承認条件に従うことを確認する。 */
  @Test
  void unknownExternalResultAndApprovalNeverAutomaticallyRepeat() {
    for (String reason : java.util.List.of("external_result_unknown", "permission_required")) {
      var goal = setup();
      var result =
          loop((claim, run, done) ->
                  done.accept(
                      new GoalLoopService.Outcome(ChatExecutionResult.failed(reason), reason)))
              .run("project", goal.id());
      assertEquals(1, result.attempts());
      assertEquals("BLOCKED", stages.list(goal).getFirst().state());
      if (reason.equals("external_result_unknown")) {
        loop((claim, run, done) -> fail("Unknown operation replayed")).run("project", goal.id());
        assertEquals(1, goals.get("project", goal.id()).attempts());
      }
    }
  }
}
