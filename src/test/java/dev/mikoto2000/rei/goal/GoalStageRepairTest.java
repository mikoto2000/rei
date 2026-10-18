package dev.mikoto2000.rei.goal;

import static org.junit.jupiter.api.Assertions.*;

import dev.mikoto2000.rei.core.chat.ChatExecutionResult;
import org.junit.jupiter.api.Test;

class GoalStageRepairTest extends GoalStageLoopTest {
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

  @Test
  void noRepairModeStopsAtFirstFailedIndependentVerification() {
    var goal = setup();
    service.configureRepair(0);
    var result = loop((claim, run, done) -> done.accept(done())).run("project", goal.id());
    assertEquals(1, result.attempts());
    assertEquals("BLOCKED", result.status());
  }

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
