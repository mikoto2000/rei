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

@Tag("integration")
class GoalStageLoopTest {
  @TempDir Path dir;
  GoalRepository goals;
  GoalStageRepository stages;
  GoalStageService service;
  FileGoalVerifier files = new FileGoalVerifier();

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

  GoalRepository.FileCriterion criterion(String id) {
    return new GoalRepository.FileCriterion(id + ".json", "", "/ok", "true");
  }

  GoalStageDefinition stage(String id, String... dependencies) {
    return new GoalStageDefinition(
        id, null, "Produce " + id, List.of(criterion(id)), List.of(dependencies), List.of(), null);
  }

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

  void write(String id, boolean ok) {
    try {
      Files.writeString(dir.resolve(id + ".json"), "{\"ok\":" + ok + "}");
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  GoalLoopService.Outcome done() {
    return new GoalLoopService.Outcome(ChatExecutionResult.success("DONE", false));
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

  @Test
  void processRestartRequiresExplicitReconcileAndDoesNotRepeatVerifiedStage() {
    var goal = setup();
    var callback = new AtomicReference<Consumer<GoalLoopService.Outcome>>();
    var loop =
        loop(
            new GoalLoopService.Gateway() {
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
