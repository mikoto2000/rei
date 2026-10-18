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

@Tag("integration")
class GoalStageRepositoryTest {
  @TempDir Path dir;

  DriverManagerDataSource source() {
    return new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("goals.db"));
  }

  GoalRepository goals() {
    return new GoalRepository(source(), Clock.systemUTC());
  }

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

  GoalRepository.Goal goal(GoalRepository goals) {
    return goals.create(
        new AgentRunContext("human", "session", dir, "project"),
        "All outputs",
        List.of(new GoalRepository.FileCriterion("final.json", "", "/ok", "true")),
        10,
        20);
  }

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
}
