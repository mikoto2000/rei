package dev.mikoto2000.rei.evaluation;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import dev.mikoto2000.rei.goal.*;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.core.policy.ToolPermissionProperties;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Deterministic executor fixtures; network/model/job boundaries are doubles, SQLite/files are real. */
@Tag("integration")
class GoalCompletionBaselineTest {
  @TempDir Path temporary;
  @Test void executesTenScenariosAgainstExistingLoopAndSavesIndependentBaseline() throws Exception {
    var results = new ArrayList<GoalCompletionEvaluation.Result>();
    var evaluator = new GoalCompletionEvaluation();
    for (String scenario : List.of("multi-requirement", "test-repair", "long-job", "approval-resume",
        "restart-resume", "artifact-delivery", "partial-required", "duplicate-operation", "web-research", "impossible")) {
      Path root = Files.createDirectory(temporary.resolve(scenario)).toRealPath();
      var data = new DriverManagerDataSource("jdbc:sqlite:" + root.resolve("goals.db"));
      var goals = new GoalRepository(data, Clock.systemUTC());
      var criteria = List.of(new GoalRepository.FileCriterion("output.txt", sha("correct")),
          new GoalRepository.FileCriterion("checks.json", null, "/passed", "true"));
      var goal = goals.create(new AgentRunContext("source", "session", root, "project"), scenario, criteria, 3, 3);
      var calls = new AtomicInteger(); var claims = new AtomicBoolean();
      var unnecessary = new AtomicInteger(); var previousEvidence = new AtomicReference<String>();
      var callback = new AtomicReference<Consumer<GoalLoopService.Outcome>>();
      var observedClaim = new AtomicReference<GoalRepository.Claim>();
      var verifier = new FileGoalVerifier();
      GoalLoopService.Gateway gateway = (claim, run, done) -> {
        assertTrue(goals.reserveLlm(claim)); int attempt = calls.incrementAndGet();
        try {
          switch (scenario) {
            case "long-job", "restart-resume" -> {callback.set(done); observedClaim.set(claim); return;}
            case "approval-resume" -> {
              if (attempt == 1) {done.accept(new GoalLoopService.Outcome(ChatExecutionResult.failed("approval"), "permission_required")); return;}
              write(root, true);
            }
            case "test-repair" -> write(root, attempt > 1);
            case "partial-required" -> Files.writeString(root.resolve("output.txt"), "correct");
            case "impossible" -> { /* Deliberately never meets the mandatory oracle. */ }
            default -> write(root, true);
          }
          String evidence = criteria.stream().map(c -> verifier.fingerprint(root,c.relativeFile()).toString()).toList().toString();
          if (evidence.equals(previousEvidence.getAndSet(evidence)) && !verifier.verify(goals.get("project",goal.id())).satisfied()) unnecessary.incrementAndGet();
          claims.set(true);
          done.accept(new GoalLoopService.Outcome(ChatExecutionResult.success("All done", false)));
        } catch (Exception error) {throw new IllegalStateException(error);}
      };
      var loop = new GoalLoopService(goals, verifier, gateway,
          new ToolPermissionProperties(true,null,null,null), new GoalEvents(event -> {}, Clock.systemUTC()));
      if (scenario.equals("duplicate-operation")) write(root,true);
      long started = System.nanoTime();
      var end = loop.run("project", goal.id()); int interventions = 0;
      if (scenario.equals("long-job")) {
        assertEquals("RUNNING", end.status()); assertEquals(1,calls.get());
        write(root,true); claims.set(true);
        callback.get().accept(new GoalLoopService.Outcome(ChatExecutionResult.success("done",false)));
        end = goals.get("project",goal.id());
      } else if (scenario.equals("approval-resume")) {
        assertEquals("WAITING_APPROVAL",end.status()); assertEquals(1,calls.get());
        interventions++; end = loop.run("project",goal.id());
      } else if (scenario.equals("restart-resume")) {
        var restarted = new GoalRepository(data,Clock.systemUTC());
        var paused = restarted.reconcile("project",goal.id(),end.currentRunId());
        assertEquals(1,paused.llmCallsUsed()); assertFalse(goals.reserveLlm(observedClaim.get()));
        write(root,true); interventions++;
        var resumed = new GoalLoopService(restarted,verifier,(claim,run,done)->fail("No duplicate dispatch"),
            new ToolPermissionProperties(true,null,null,null),new GoalEvents(event->{},Clock.systemUTC()));
        end = resumed.run("project",goal.id());
        callback.get().accept(new GoalLoopService.Outcome(ChatExecutionResult.success("late",false)));
        assertEquals("COMPLETED",restarted.get("project",goal.id()).status());
      }
      boolean unmet = Set.of("partial-required","impossible").contains(scenario);
      assertEquals(unmet ? "BLOCKED" : "COMPLETED",end.status(),scenario);
      assertTrue(end.attempts() <= 3); assertTrue(end.llmCallsUsed() <= 3);
      var checks = new ArrayList<GoalCompletionEvaluation.Condition>();
      for (int i=0;i<criteria.size();i++) {
        var verified = verifier.verify(root,criteria.get(i));
        checks.add(new GoalCompletionEvaluation.Condition("requirement-"+i,true,verified.satisfied(),verified.reason()));
      }
      if (scenario.equals("artifact-delivery")) checks.add(new GoalCompletionEvaluation.Condition(
          "handover",true,false,"Fixture saves a file; user receipt is not instrumented"));
      int repairs = scenario.equals("test-repair") ? calls.get()-1 : 0;
      var observation = new GoalCompletionEvaluation.Observation(claims.get(),true,repairs,repairs>0?1:0,
          interventions,unnecessary.get(),0,0,GoalCompletionEvaluation.Measurement.known((System.nanoTime()-started)/1_000_000),
          GoalCompletionEvaluation.Measurement.known(end.llmCallsUsed()),
          GoalCompletionEvaluation.Measurement.unavailable("Scripted gateway; no model token usage"));
      results.add(evaluator.score(scenario,observation,checks));
    }
    var report = evaluator.aggregate(results);
    assertEquals(10,report.cases()); assertEquals(0.7,report.verifiedCompletionRate());
    assertEquals(0.3,report.falseCompletionRate()); assertEquals(0.3,report.endedWithUnmetRate());
    assertEquals(1.0,report.repairSuccessRate().value()); assertEquals(2,report.interventions());
    assertNull(report.tokens().value()); assertEquals(4,report.unnecessaryIterations());
    var payload = new LinkedHashMap<String,Object>();
    payload.put("schemaVersion",1); payload.put("baselineRevision","cd29f4e7f1c3d5e5f361a0fec576f8bed821fff0");
    payload.put("scope","Scripted Goal gateway; real SQLite and independent file/JSON oracles; jobs/search/model are doubles");
    payload.put("limitations",List.of("Repair is scripted, not autonomous model repair", "Job delay is a deferred callback, not real wall-clock process latency", "Web evidence is scripted, not live retrieval", "Counters for unnecessary iterations, duplicates and permission violations cover this fixture only", "Model calls are Goal reservation counts, not provider calls", "Handover receipt is unavailable and deliberately fails its mandatory oracle"));
    payload.put("report",report); payload.put("results",results);
    Path output = Path.of(System.getProperty("rei.evaluation.output","target/goal-completion-baseline.json"));
    Files.createDirectories(output.toAbsolutePath().getParent());
    Files.writeString(output,new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(payload),StandardCharsets.UTF_8);
  }
  private static void write(Path root,boolean passed) throws Exception {
    Files.writeString(root.resolve("output.txt"),"correct");
    Files.writeString(root.resolve("checks.json"),"{\"passed\":"+passed+"}");
  }
  private static String sha(String value) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
  }
}


