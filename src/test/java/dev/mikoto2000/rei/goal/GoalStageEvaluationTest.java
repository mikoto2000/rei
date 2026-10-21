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
 * 目標段階評価の結果と状態の契約を検証するテスト。
 *
 * <p>Same Goal requirements/budgets with real SQLite and scripted model/external-effect boundaries.
 */
@Tag("integration")
class GoalStageEvaluationTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path temporary;

  /** 検証条件で扱う動作モードの選択肢。 */
  enum Mode {
    /** 検証に使う旧形式。 */
    LEGACY,
    /** 検証に使う段階一覧。 */
    STAGES,
    /** 検証に使う修復。 */
    REPAIR,
    /** 検証に使う分割。 */
    SPLIT
  }

  /**
   * 検証条件の再現に使う計測値の情報。
   *
   * @param scenario 検証に使うscenario
   * @param mode 検証に使う動作モード
   * @param status 検証に使う状態
   * @param independentlyVerified 検証に使うindependently確認済み
   * @param falseCompletion 検証に使う無効完了
   * @param resumeSucceeded 検証に使う再開成功
   * @param duplicateOperations 検証に使う重複の有無操作一覧
   * @param humanInterventions 検証に使う利用者追加入力一覧
   * @param elapsedNanos 検証に使う経過時間ナノ秒数
   * @param runs 検証に使う実行一覧
   * @param llmReservations 検証に使う言語モデル予約一覧
   * @param tokens 検証に使うトークン数
   * @param tokenLimitation 検証に使うトークン制約
   */
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

  /** 目標の達成管理で、保存した情報を、再起動や復元後の処理へ正しく引き継ぐことを確認する。 */
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

  /** 検証条件を再現するための検証条件。 */
  static final class Fixture {
    /** 検証に使う作業ルート。 */
    final Path root;

    /** 検証に使う動作モード。 */
    final Mode mode;

    /** 検証に使うscenario。 */
    final String scenario;

    /** 検証に使うデータ。 */
    final DriverManagerDataSource data;

    /** 検証に使う目標一覧。 */
    GoalRepository goals;

    /** 検証に使う段階一覧。 */
    GoalStageRepository stages;

    /** 検証に使うサービス。 */
    GoalStageService service;

    /** 検証に使う目標。 */
    GoalRepository.Goal goal;

    /** 検証に使うファイル一覧。 */
    final FileGoalVerifier files = new FileGoalVerifier();

    /** 検証に使う待機中。 */
    Consumer<GoalLoopService.Outcome> pending;

    /** 検証に使う確認済み影響。 */
    final Map<String, Integer> confirmedEffects = new LinkedHashMap<>();

    /** 検証に使うcrashed。 */
    boolean crashed;

    /** 検証に使うdispatches。 */
    int dispatches;

    /** 検証に使う追加入力一覧。 */
    int interventions;

    /** 検証条件に必要な値を受け取り、Fixtureを用意する。 */
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

    /** {@code reopen} の条件を再現するため、検証用の操作や状態を扱う。 */
    void reopen() {
      goals = new GoalRepository(data, Clock.systemUTC());
      stages = new GoalStageRepository(Clock.systemUTC(), goals);
      service = new GoalStageService(stages, new GoalStageVerifier(files), goals);
      service.configureRepair(mode == Mode.STAGES ? 0 : 2);
      service.configureSplit(mode == Mode.SPLIT);
    }

    /** {@code criterion} の条件を再現するための補助情報を返す。 */
    GoalRepository.FileCriterion criterion(String id) {
      return new GoalRepository.FileCriterion(id + ".json", "", "/ok", "true");
    }

    /** {@code definition} の条件を再現するための補助情報を返す。 */
    GoalStageDefinition definition(String id, String parent, List<String> dependencies) {
      return new GoalStageDefinition(
          id, parent, "Produce " + id, List.of(criterion(id)), dependencies, List.of(), null);
    }

    /** 検証用に、指定された内容を書き込み先へ反映する。 */
    void write(String id, boolean value, boolean effect) throws Exception {
      Files.writeString(
          root.resolve(id + ".json"), "{\"ok\":" + value + ",\"iteration\":" + dispatches + "}");
      if (effect) confirmedEffects.merge(id, 1, Integer::sum);
    }

    /** {@code success} の条件を再現するための補助情報を返す。 */
    GoalLoopService.Outcome success() {
      return new GoalLoopService.Outcome(ChatExecutionResult.success("DONE", false));
    }

    /** {@code loop} の条件を再現するための補助情報を返す。 */
    GoalLoopService loop() {
      var loop =
          new GoalLoopService(
              goals,
              files,
              new GoalLoopService.Gateway() {
                /** {@code dispatch} の条件を再現するため、検証用の操作や状態を扱う。 */
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

                /** 検証用に、目標の達成管理において、入力先Flightかどうかを判定する。 */
                public boolean isInFlight(GoalRepository.Goal ignored) {
                  return false;
                }
              },
              new ToolPermissionProperties(true, null, null, null),
              new GoalEvents(e -> {}, Clock.systemUTC()));
      if (mode != Mode.LEGACY) loop.configureStages(service);
      return loop;
    }

    /** 検証用に、要求された処理を実行する。 */
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
