package dev.mikoto2000.rei.goal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.ChatExecutionResult;
import dev.mikoto2000.rei.core.policy.ToolPermissionProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Same Goal requirements/budgets with real SQLite and scripted model/external-effect boundaries.
 */
@Tag("integration")
class GoalStageEvaluationTest {
  @TempDir Path temporary;

  enum Mode {
    LEGACY,
    STAGES,
    REPAIR,
    SPLIT
  }

  record Measurement(
      String scenario,
      Mode mode,
      String status,
      boolean independentlyVerified,
      boolean falseCompletion,
      Boolean resumeSucceeded,
      int duplicateOperations,
      int humanInterventions,
      long elapsedNanos,
      int runs,
      int llmReservations,
      Long tokens,
      String tokenLimitation) {}

  @Test
  void comparesFourModesAcrossTenSafetyCasesAndTwoRecoveryCases() throws Exception {
    var results = new ArrayList<Measurement>();
    for (var mode : Mode.values()) {
      for (String scenario :
          List.of(
              "normal",
              "intermediate-failure",
              "damaged-predecessor",
              "process-exit",
              "unknown-effect",
              "repair-budget",
              "parent-unmet",
              "false-done",
              "weakened-plan",
              "duplicate-resume",
              "repair-recovery",
              "decomposition-recovery")) {
        var fixture =
            new Fixture(
                Files.createDirectories(temporary.resolve(mode + "-" + scenario)).toRealPath(),
                mode,
                scenario);
        results.add(fixture.execute());
      }
    }
    assertEquals(48, results.size());
    assertTrue(results.stream().noneMatch(Measurement::falseCompletion));
    assertTrue(results.stream().allMatch(r -> r.runs() <= 6 && r.llmReservations() <= 6));
    assertTrue(
        results.stream()
            .filter(r -> r.resumeSucceeded() != null)
            .allMatch(r -> r.resumeSucceeded()));
    assertTrue(
        results.stream()
            .filter(r -> r.mode() != Mode.LEGACY)
            .allMatch(r -> r.duplicateOperations() == 0));
    assertTrue(
        results.stream()
            .anyMatch(
                r ->
                    r.mode() == Mode.SPLIT
                        && r.scenario().equals("decomposition-recovery")
                        && r.independentlyVerified()));
    assertFalse(
        results.stream()
            .anyMatch(
                r ->
                    r.mode() == Mode.REPAIR
                        && r.scenario().equals("decomposition-recovery")
                        && r.independentlyVerified()));
    var aggregates = new LinkedHashMap<String, Object>();
    for (var mode : Mode.values()) {
      var selected = results.stream().filter(r -> r.mode() == mode).toList();
      var summary = new LinkedHashMap<String, Object>();
      summary.put("cases", selected.size());
      summary.put(
          "verifiedGoalCompletionRate",
          selected.stream().filter(Measurement::independentlyVerified).count()
              / (double) selected.size());
      summary.put(
          "falseGoalCompletionRate",
          selected.stream().filter(Measurement::falseCompletion).count()
              / (double) selected.size());
      summary.put(
          "resumeSuccessRate",
          selected.stream().filter(r -> Boolean.TRUE.equals(r.resumeSucceeded())).count() / 2.0);
      summary.put(
          "duplicateOperations",
          selected.stream().mapToInt(Measurement::duplicateOperations).sum());
      summary.put(
          "humanInterventions", selected.stream().mapToInt(Measurement::humanInterventions).sum());
      summary.put("elapsedNanos", selected.stream().mapToLong(Measurement::elapsedNanos).sum());
      summary.put("runs", selected.stream().mapToInt(Measurement::runs).sum());
      summary.put(
          "llmReservations", selected.stream().mapToInt(Measurement::llmReservations).sum());
      summary.put("tokens", null);
      aggregates.put(mode.name(), summary);
    }
    var report = new LinkedHashMap<String, Object>();
    report.put("schemaVersion", 1);
    report.put("baselineMainRevision", "c6da942a277fc47a827e4ed9f37fc7b850227c23");
    report.put(
        "scope",
        "Real Goal loop/SQLite/file predicates; scripted gateway, proposals, process exit, external effects and permissions; no provider calls");
    report.put(
        "limitations",
        List.of(
            "Synthetic fixture rates do not estimate live-model completion improvements",
            "Duplicate count is replay of scripted confirmed effects, not exactly-once external API proof",
            "LLM reservations are fixture budget charges; tokens unavailable because no model is used",
            "All modes use the same final file requirements and caps; Stage attempts currently cost one Run each",
            "Repair/split proposals are scripted capabilities; live generation quality is unmeasured"));
    report.put("summary", aggregates);
    report.put("measurements", results);
    var output = Path.of("target/goal-stage-evaluation.json");
    Files.createDirectories(output.getParent());
    new com.fasterxml.jackson.databind.ObjectMapper()
        .writerWithDefaultPrettyPrinter()
        .writeValue(output.toFile(), report);
  }

  static final class Fixture {
    final Path root;
    final Mode mode;
    final String scenario;
    final DriverManagerDataSource data;
    GoalRepository goals;
    GoalStageRepository stages;
    GoalStageService service;
    GoalRepository.Goal goal;
    final FileGoalVerifier files = new FileGoalVerifier();
    Consumer<GoalLoopService.Outcome> pending;
    final Map<String, Integer> confirmedEffects = new LinkedHashMap<>();
    boolean crashed;
    int dispatches;
    int interventions;

    Fixture(Path root, Mode mode, String scenario) {
      this.root = root;
      this.mode = mode;
      this.scenario = scenario;
      data = new DriverManagerDataSource("jdbc:sqlite:" + root.resolve("goals.db"));
      reopen();
      goal =
          goals.create(
              new AgentRunContext("human", "session", root, "project"),
              scenario,
              List.of(criterion("a"), criterion("b")),
              6,
              scenario.equals("repair-budget") ? 2 : 6);
      if (mode != Mode.LEGACY) {
        var plan =
            new ArrayList<>(
                List.of(definition("a", null, List.of()), definition("b", null, List.of("a"))));
        if (scenario.equals("parent-unmet")) plan.add(definition("c", "a", List.of()));
        stages.define(goal, 0, plan);
      }
    }

    void reopen() {
      goals = new GoalRepository(data, Clock.systemUTC());
      stages = new GoalStageRepository(Clock.systemUTC(), goals);
      service = new GoalStageService(stages, new GoalStageVerifier(files), goals);
      service.configureRepair(mode == Mode.STAGES ? 0 : 2);
      service.configureSplit(mode == Mode.SPLIT);
    }

    GoalRepository.FileCriterion criterion(String id) {
      return new GoalRepository.FileCriterion(id + ".json", "", "/ok", "true");
    }

    GoalStageDefinition definition(String id, String parent, List<String> dependencies) {
      return new GoalStageDefinition(
          id, parent, "Produce " + id, List.of(criterion(id)), dependencies, List.of(), null);
    }

    void write(String id, boolean value, boolean effect) throws Exception {
      Files.writeString(
          root.resolve(id + ".json"), "{\"ok\":" + value + ",\"iteration\":" + dispatches + "}");
      if (effect) confirmedEffects.merge(id, 1, Integer::sum);
    }

    GoalLoopService.Outcome success() {
      return new GoalLoopService.Outcome(ChatExecutionResult.success("DONE", false));
    }

    GoalLoopService loop() {
      var loop =
          new GoalLoopService(
              goals,
              files,
              new GoalLoopService.Gateway() {
                public void dispatch(
                    GoalRepository.Claim claim,
                    String run,
                    Consumer<GoalLoopService.Outcome> complete) {
                  try {
                    var active = stages.forRun(goal, run);
                    String target = active == null ? "all" : active.stageId();
                    if (active != null
                        && service.observeRun(goals.get("project", goal.id()), run).result()
                            == GoalStageVerifier.Result.VERIFIED) {
                      complete.accept(success());
                      return;
                    }
                    assertTrue(goals.reserveLlm(claim));
                    dispatches++;
                    switch (scenario) {
                      case "unknown-effect" -> {
                        complete.accept(
                            new GoalLoopService.Outcome(
                                ChatExecutionResult.failed("unknown"), "external_result_unknown"));
                        return;
                      }
                      case "intermediate-failure" -> {
                        if (target.equals("a")) write("a", true, true);
                        else {
                          if (target.equals("all")) write("a", true, true);
                          complete.accept(
                              new GoalLoopService.Outcome(
                                  ChatExecutionResult.failed("middle"), "execution_failed"));
                          return;
                        }
                      }
                      case "process-exit", "duplicate-resume" -> {
                        if (target.equals("a")) write("a", true, true);
                        else if (!crashed) {
                          if (target.equals("all")) write("a", true, true);
                          crashed = true;
                          pending = complete;
                          return;
                        } else {
                          if (target.equals("all")) write("a", true, true);
                          write("b", true, true);
                        }
                      }
                      case "false-done" -> {
                        /* Model claim supplies no evidence. */
                      }
                      case "weakened-plan" -> {
                        if (mode != Mode.LEGACY) {
                          boolean rejected = false;
                          try {
                            service.proposeSplit(
                                new AgentRunContext(run, "session", root, "project"),
                                goal.id(),
                                1,
                                List.of(definition("a", "a", List.of())));
                          } catch (IllegalArgumentException | IllegalStateException expected) {
                            rejected = true;
                          }
                          assertTrue(rejected);
                          assertEquals(1, stages.planVersion(goal));
                        }
                      }
                      case "repair-budget" -> {
                        write("a", false, false);
                        if (target.equals("all")) write("b", true, false);
                      }
                      case "parent-unmet" -> {
                        if (target.equals("c")) write("c", true, true);
                        else if (target.equals("all")) {
                          write("b", true, false);
                          write("c", true, false);
                        }
                      }
                      case "decomposition-recovery" -> {
                        if (target.equals("a")) {
                          if (mode == Mode.SPLIT && active.number() == 2)
                            service.proposeSplit(
                                new AgentRunContext(run, "session", root, "project"),
                                goal.id(),
                                1,
                                List.of(definition("c", "a", List.of())));
                        } else if (target.equals("c")) {
                          write("c", true, true);
                          write("a", true, true);
                        } else if (target.equals("b")) write("b", true, true);
                      }
                      case "repair-recovery" -> {
                        if (target.equals("all")) {
                          write("a", dispatches > 1, false);
                          write("b", true, false);
                        } else write(target, !target.equals("a") || active.number() > 1, false);
                      }
                      default -> {
                        if (target.equals("all")) {
                          write("a", true, true);
                          write("b", true, true);
                        } else write(target, true, true);
                        if (scenario.equals("damaged-predecessor") && !target.equals("a"))
                          write("a", false, false);
                      }
                    }
                    complete.accept(success());
                  } catch (Exception error) {
                    throw new IllegalStateException(error);
                  }
                }

                public boolean isInFlight(GoalRepository.Goal ignored) {
                  return false;
                }
              },
              new ToolPermissionProperties(true, null, null, null),
              new GoalEvents(e -> {}, Clock.systemUTC()));
      if (mode != Mode.LEGACY) loop.configureStages(service);
      return loop;
    }

    Measurement execute() {
      long start = System.nanoTime();
      var loop = loop();
      var result = loop.run("project", goal.id());
      Boolean resumed = null;
      if (pending != null) {
        assertEquals("RUNNING", result.status());
        var callback = pending;
        reopen();
        loop = loop();
        loop.reconcile("project", goal.id(), result.currentRunId());
        interventions++;
        callback.accept(success());
        callback.accept(success());
        result = loop.run("project", goal.id());
        callback.accept(success());
        resumed = result.status().equals("COMPLETED") && files.verify(result).satisfied();
      }
      boolean oracle = files.verify(result).satisfied();
      int duplicates = confirmedEffects.values().stream().mapToInt(n -> Math.max(0, n - 1)).sum();
      return new Measurement(
          scenario,
          mode,
          result.status(),
          result.status().equals("COMPLETED") && oracle,
          result.status().equals("COMPLETED") && !oracle,
          resumed,
          duplicates,
          interventions,
          System.nanoTime() - start,
          result.attempts(),
          result.llmCallsUsed(),
          null,
          "No live model in deterministic fixture");
    }
  }
}
