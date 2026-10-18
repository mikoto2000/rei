package dev.mikoto2000.rei.goal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import java.util.List;
import org.junit.jupiter.api.Test;

class GoalStagePlanChangeTest extends GoalStageLoopTest {
  GoalStageDefinition child(String id, String parent) {
    return new GoalStageDefinition(
        id, parent, "Build child", List.of(criterion(id)), List.of(), List.of(), null);
  }

  @Test
  void childrenCannotCompleteParentAndPlanHistoryRetainsConditionsAndBudgets() {
    var goal = setup();
    service.configureRepair(0);
    assertEquals(
        "BLOCKED",
        loop((claim, run, complete) -> complete.accept(done())).run("project", goal.id()).status());
    stages.define(
        goals.get("project", goal.id()), 1, List.of(stage("a"), stage("b", "a"), child("c", "a")));
    service.configureRepair(2);
    var result =
        loop((claim, run, complete) -> {
              var active = stages.forRun(goal, run);
              if (active.stageId().equals("c")) write("c", true);
              complete.accept(done());
            })
            .run("project", goal.id());
    assertEquals("BLOCKED", result.status());
    assertEquals(
        "VERIFIED",
        stages.list(goal).stream()
            .filter(s -> s.definition().id().equals("c"))
            .findFirst()
            .orElseThrow()
            .state());
    assertNotEquals("VERIFIED", stages.list(goal).getFirst().state());
    assertEquals(2, stages.planHistory(goal).size());
    assertEquals(10, result.maxRuns());
    assertEquals(stage("a"), stages.list(goal).getFirst().definition());
  }

  @Test
  void modelProposalIsValidatedAndAppliedOnlyByHostAfterUnverifiedAttempt() {
    var goal = setup();
    var result =
        loop((claim, run, complete) -> {
              var active = stages.forRun(goal, run);
              if (active.stageId().equals("a") && active.number() == 2) {
                service.proposeSplit(
                    new AgentRunContext(run, "session", dir, "project"),
                    goal.id(),
                    1,
                    List.of(child("c", "a")));
                assertEquals(1, stages.planVersion(goal));
              }
              if (active.stageId().equals("c")) {
                write("c", true);
                write("a", true);
              }
              if (active.stageId().equals("b")) write("b", true);
              complete.accept(done());
            })
            .run("project", goal.id());
    assertEquals("COMPLETED", result.status());
    assertEquals(2, stages.planVersion(goal));
    assertTrue(stages.list(goal).stream().allMatch(s -> s.state().equals("VERIFIED")));
    assertEquals(10, result.maxRuns());
  }

  @Test
  void directRepositoryAdmissionCannotBypassDependencies() {
    var goal = setup();
    var claim = goals.claim("project", goal.id());
    var run = goals.beginAttempt(claim);
    assertThrows(
        IllegalStateException.class, () -> stages.begin(claim, run, stages.list(goal).get(1)));
  }

  @Test
  void stagePlanFreezesWholeGoalCompletionContract() {
    var goal = setup();
    var definition =
        new GoalCompletionGate.Definition(
            List.of(new GoalRepository.FileCriterion("evidence", "a".repeat(64))),
            null,
            List.of(),
            List.of(),
            null);
    assertThrows(
        IllegalStateException.class,
        () ->
            goals.defineCompletion(
                new AgentRunContext("human", "session", dir, "project"), goal.id(), definition));
  }

  @Test
  void completionTransactionRollbackKeepsEvidenceAndAttemptConsistent() {
    var goal = setup();
    var claim = goals.claim("project", goal.id());
    var run = goals.beginAttempt(claim);
    var attempt = stages.begin(claim, run, stages.list(goal).getFirst());
    write("a", true);
    var observation =
        new GoalStageVerifier(files).verify(goal, stages.list(goal).getFirst(), stages.list(goal));
    assertThrows(
        IllegalStateException.class,
        () ->
            goals.atomic(
                () -> {
                  assertTrue(stages.finish(claim, attempt, observation));
                  goals.recordAttempt(claim, run, "VERIFIED", "criteria_verified");
                  throw new IllegalStateException("Simulated failure before commit");
                }));
    assertEquals("RUNNING", stages.list(goal).getFirst().state());
    assertEquals("RUNNING", stages.attempts(goal).getFirst().status());
    assertTrue(stages.evidence(goal).isEmpty());
    assertTrue(goals.active(claim, run));
  }
}
