package dev.mikoto2000.rei.goal;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Additive SQLite storage. All writes join the existing Goal transaction and claim fence. */
@Repository
public class GoalStageRepository {
  public record Evidence(
      String goalId,
      String stageId,
      String attemptId,
      long planVersion,
      String conditionVersion,
      Map<String, String> artifactVersions,
      Map<String, Long> dependencyVersions,
      GoalStageVerifier.Result result,
      String verifiedAt,
      String reference) {
    public Evidence {
      artifactVersions = Map.copyOf(artifactVersions);
      dependencyVersions = Map.copyOf(dependencyVersions);
    }
  }

  public record Stage(
      GoalStageDefinition definition,
      long planVersion,
      long version,
      String state,
      String reason,
      Evidence evidence,
      GoalCompletionGate.Proof proof) {}

  public record Attempt(
      String id,
      String stageId,
      String runId,
      int number,
      long planVersion,
      String status,
      String reason,
      String startedAt,
      String finishedAt,
      int callsUsed,
      long tokensUsed,
      String observation) {}

  private static final ObjectMapper JSON =
      new ObjectMapper(
              com.fasterxml.jackson.core.JsonFactory.builder()
                  .enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                  .streamReadConstraints(
                      com.fasterxml.jackson.core.StreamReadConstraints.builder()
                          .maxNestingDepth(32)
                          .maxStringLength(32768)
                          .build())
                  .build())
          .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  private final JdbcClient db;
  private final Clock clock;
  private final GoalRepository goals;

  public GoalStageRepository(Clock clock, GoalRepository goals) {
    db = goals.jdbc();
    this.clock = clock;
    this.goals = goals;
    db.sql(
            "CREATE TABLE IF NOT EXISTS agent_goal_stage_plans(goal TEXT PRIMARY KEY,version INTEGER NOT NULL,definition TEXT NOT NULL,changed_at TEXT NOT NULL)")
        .update();
    db.sql(
            "CREATE TABLE IF NOT EXISTS agent_goal_stages(goal TEXT NOT NULL,id TEXT NOT NULL,ordinal INTEGER NOT NULL,definition TEXT NOT NULL,plan_version INTEGER NOT NULL,version INTEGER NOT NULL,state TEXT NOT NULL,reason TEXT NOT NULL DEFAULT '',evidence TEXT,proof TEXT,PRIMARY KEY(goal,id))")
        .update();
    db.sql(
            "CREATE TABLE IF NOT EXISTS agent_goal_stage_attempts(id TEXT PRIMARY KEY,goal TEXT NOT NULL,stage TEXT NOT NULL,run TEXT NOT NULL,number INTEGER NOT NULL,plan_version INTEGER NOT NULL,status TEXT NOT NULL,reason TEXT NOT NULL DEFAULT '',started_at TEXT NOT NULL,finished_at TEXT NOT NULL DEFAULT '',calls_start INTEGER NOT NULL,tokens_start INTEGER NOT NULL,calls_used INTEGER NOT NULL DEFAULT 0,tokens_used INTEGER NOT NULL DEFAULT 0,observation TEXT NOT NULL DEFAULT '',UNIQUE(goal,stage,number))")
        .update();
    db.sql(
            "CREATE INDEX IF NOT EXISTS agent_goal_stage_attempt_run ON agent_goal_stage_attempts(goal,run)")
        .update();
    db.sql(
            "CREATE TABLE IF NOT EXISTS agent_goal_stage_evidence(id TEXT PRIMARY KEY,goal TEXT NOT NULL,stage TEXT NOT NULL,evidence TEXT NOT NULL)")
        .update();
    db.sql(
            "CREATE TABLE IF NOT EXISTS agent_goal_stage_plan_history(goal TEXT NOT NULL,version INTEGER NOT NULL,definition TEXT NOT NULL,changed_at TEXT NOT NULL,PRIMARY KEY(goal,version))")
        .update();
  }

  static String encode(Object value) {
    try {
      String result = JSON.writeValueAsString(value);
      if (result.length() > 262144) throw new IllegalArgumentException("Stage data exceeds 256KiB");
      return result;
    } catch (java.io.IOException invalid) {
      throw new IllegalArgumentException("Invalid stage JSON", invalid);
    }
  }

  static <T> T decode(String value, Class<T> type) {
    if (value == null) return null;
    if (value.length() > 262144) throw new IllegalArgumentException("Stage data exceeds 256KiB");
    try {
      return JSON.readValue(value, type);
    } catch (java.io.IOException invalid) {
      throw new IllegalArgumentException("Invalid stage JSON", invalid);
    }
  }

  public static List<GoalStageDefinition> parsePlan(String json) {
    return List.of(decode(json, GoalStageDefinition[].class));
  }

  public long planVersion(GoalRepository.Goal goal) {
    goals.get(goal.projectId(), goal.id());
    return db.sql("SELECT version FROM agent_goal_stage_plans WHERE goal=?")
        .param(goal.id())
        .query(Long.class)
        .optional()
        .orElse(0L);
  }

  public List<Stage> list(GoalRepository.Goal goal) {
    goals.get(goal.projectId(), goal.id());
    return db.sql("SELECT * FROM agent_goal_stages WHERE goal=? ORDER BY ordinal")
        .param(goal.id())
        .query(
            (rs, n) ->
                new Stage(
                    decode(rs.getString("definition"), GoalStageDefinition.class),
                    rs.getLong("plan_version"),
                    rs.getLong("version"),
                    rs.getString("state"),
                    rs.getString("reason"),
                    decode(rs.getString("evidence"), Evidence.class),
                    decode(rs.getString("proof"), GoalCompletionGate.Proof.class)))
        .list();
  }

  private void lock(GoalRepository.Goal goal) {
    if (db.sql("UPDATE agent_goals SET attempts=attempts WHERE project=? AND id=?")
            .params(goal.projectId(), goal.id())
            .update()
        != 1) throw new IllegalStateException("Goal disappeared");
  }

  public List<Stage> define(
      GoalRepository.Goal expected, long expectedVersion, List<GoalStageDefinition> plan) {
    return goals.atomic(
        () -> {
          lock(expected);
          var goal = goals.get(expected.projectId(), expected.id());
          if (goal.status().equals("RUNNING")
              || goal.status().equals("COMPLETED")
              || goal.status().equals("CANCELLED")
              || planVersion(goal) != expectedVersion)
            throw new IllegalStateException("Stop Goal and use the observed plan version");
          GoalStagePlanValidator.validate(goal, plan);
          var previous = list(goal);
          if (expectedVersion == 0 && goal.attempts() > 0)
            throw new IllegalStateException("Define stages before the first Goal Run");
          if (!previous.isEmpty()) {
            GoalStagePlanValidator.preserve(
                previous.stream().map(Stage::definition).toList(), plan);
            if (expectedVersion >= 5 || plan.size() == previous.size())
              throw new IllegalArgumentException("Plan split limit or unchanged plan");
            for (var added : plan) {
              if (previous.stream().anyMatch(s -> s.definition().id().equals(added.id()))) continue;
              var parent =
                  previous.stream()
                      .filter(s -> s.definition().id().equals(added.parentStageId()))
                      .findFirst()
                      .orElseThrow(() -> new IllegalArgumentException("Split an existing stage"));
              if (!java.util.Set.of("REPAIRING", "BLOCKED").contains(parent.state())
                  || !GoalRepairDiagnosis.of(parent.reason()).repairable())
                throw new IllegalStateException(
                    "Only an independently failed repairable stage can split");
            }
          }
          long version = expectedVersion + 1;
          String now = clock.instant().toString(), encoded = encode(plan);
          db.sql(
                  "INSERT INTO agent_goal_stage_plans(goal,version,definition,changed_at) VALUES(?,?,?,?) ON CONFLICT(goal) DO UPDATE SET version=excluded.version,definition=excluded.definition,changed_at=excluded.changed_at")
              .params(goal.id(), version, encoded, now)
              .update();
          db.sql(
                  "INSERT INTO agent_goal_stage_plan_history(goal,version,definition,changed_at) VALUES(?,?,?,?)")
              .params(goal.id(), version, encoded, now)
              .update();
          for (int i = 0; i < plan.size(); i++) {
            var item = plan.get(i);
            db.sql(
                    "INSERT OR IGNORE INTO agent_goal_stages(goal,id,ordinal,definition,plan_version,version,state) VALUES(?,?,?,?,?,1,'TODO')")
                .params(goal.id(), item.id(), i, encode(item), version)
                .update();
          }
          db.sql(
                  "UPDATE agent_goal_stages SET plan_version=?,version=version+1,state=CASE WHEN state='VERIFIED' THEN 'STALE' ELSE state END,reason=CASE WHEN state='VERIFIED' THEN 'stage_plan_changed' ELSE reason END WHERE goal=?")
              .params(version, goal.id())
              .update();
          for (var item : plan)
            if (item.parentStageId() != null
                && previous.stream().noneMatch(s -> s.definition().id().equals(item.id())))
              db.sql(
                      "UPDATE agent_goal_stages SET state='DECOMPOSED',version=version+1 WHERE goal=? AND id=?")
                  .params(goal.id(), item.parentStageId())
                  .update();
          return list(goal);
        });
  }

  public List<Attempt> attempts(GoalRepository.Goal goal) {
    goals.get(goal.projectId(), goal.id());
    return db.sql("SELECT * FROM agent_goal_stage_attempts WHERE goal=? ORDER BY rowid")
        .param(goal.id())
        .query(
            (rs, n) ->
                new Attempt(
                    rs.getString("id"),
                    rs.getString("stage"),
                    rs.getString("run"),
                    rs.getInt("number"),
                    rs.getLong("plan_version"),
                    rs.getString("status"),
                    rs.getString("reason"),
                    rs.getString("started_at"),
                    rs.getString("finished_at"),
                    rs.getInt("calls_used"),
                    rs.getLong("tokens_used"),
                    rs.getString("observation")))
        .list();
  }

  public List<Evidence> evidence(GoalRepository.Goal goal) {
    goals.get(goal.projectId(), goal.id());
    return db.sql("SELECT evidence FROM agent_goal_stage_evidence WHERE goal=? ORDER BY rowid")
        .param(goal.id())
        .query((rs, n) -> decode(rs.getString(1), Evidence.class))
        .list();
  }

  public List<String> planHistory(GoalRepository.Goal goal) {
    goals.get(goal.projectId(), goal.id());
    return db.sql(
            "SELECT definition FROM agent_goal_stage_plan_history WHERE goal=? ORDER BY version")
        .param(goal.id())
        .query(String.class)
        .list();
  }

  public Attempt begin(GoalRepository.Claim claim, String run, Stage expected) {
    return goals.atomic(
        () -> {
          lock(claim.goal());
          if (!goals.active(claim, run)) throw new IllegalStateException("Goal Run inactive");
          String id = UUID.randomUUID().toString();
          if (db.sql(
                      "UPDATE agent_goal_stages SET state='RUNNING',version=version+1 WHERE goal=? AND id=? AND version=? AND plan_version=? AND state IN ('TODO','STALE','REPAIRING')")
                  .params(
                      claim.goal().id(),
                      expected.definition().id(),
                      expected.version(),
                      expected.planVersion())
                  .update()
              != 1) throw new IllegalStateException("Stage changed or transition forbidden");
          var goal = goals.get(claim.goal().projectId(), claim.goal().id());
          int number =
              (int)
                      attempts(goal).stream()
                          .filter(a -> a.stageId().equals(expected.definition().id()))
                          .count()
                  + 1;
          db.sql(
                  "INSERT INTO agent_goal_stage_attempts(id,goal,stage,run,number,plan_version,status,started_at,calls_start,tokens_start) VALUES(?,?,?,?,?,?,'RUNNING',?,?,?)")
              .params(
                  id,
                  goal.id(),
                  expected.definition().id(),
                  run,
                  number,
                  expected.planVersion(),
                  clock.instant().toString(),
                  goal.llmCallsUsed(),
                  goal.totalTokens())
              .update();
          return attempts(goal).stream().filter(a -> a.id().equals(id)).findFirst().orElseThrow();
        });
  }

  public Attempt forRun(GoalRepository.Goal goal, String run) {
    return attempts(goal).stream()
        .filter(a -> a.runId().equals(run) && a.status().equals("RUNNING"))
        .findFirst()
        .orElse(null);
  }

  public boolean finish(
      GoalRepository.Claim claim, Attempt attempt, GoalStageVerifier.Observation observed) {
    return goals.atomic(
        () -> {
          lock(claim.goal());
          if (!goals.active(claim, attempt.runId())) return false;
          var goal = goals.get(claim.goal().projectId(), claim.goal().id());
          var stage =
              list(goal).stream()
                  .filter(s -> s.definition().id().equals(attempt.stageId()))
                  .findFirst()
                  .orElseThrow();
          if (stage.planVersion() != attempt.planVersion() || !stage.state().equals("RUNNING"))
            return false;
          String state =
              observed.result() == GoalStageVerifier.Result.VERIFIED
                  ? "VERIFIED"
                  : observed.result() == GoalStageVerifier.Result.UNSATISFIED
                      ? "REPAIRING"
                      : "BLOCKED";
          if (db.sql(
                      "UPDATE agent_goal_stage_attempts SET status=?,reason=?,finished_at=?,calls_used=?-calls_start,tokens_used=?-tokens_start,observation=? WHERE id=? AND goal=? AND status='RUNNING' AND plan_version=?")
                  .params(
                      observed.result().name(),
                      observed.reason(),
                      clock.instant().toString(),
                      goal.llmCallsUsed(),
                      goal.totalTokens(),
                      encode(observed),
                      attempt.id(),
                      goal.id(),
                      attempt.planVersion())
                  .update()
              != 1) return false;
          String reference = UUID.randomUUID().toString();
          var evidence =
              new Evidence(
                  goal.id(),
                  attempt.stageId(),
                  attempt.id(),
                  attempt.planVersion(),
                  encode(stage.definition()),
                  observed.artifactVersions(),
                  observed.dependencyVersions(),
                  observed.result(),
                  clock.instant().toString(),
                  reference);
          db.sql("INSERT INTO agent_goal_stage_evidence(id,goal,stage,evidence) VALUES(?,?,?,?)")
              .params(reference, goal.id(), attempt.stageId(), encode(evidence))
              .update();
          if (db.sql(
                      "UPDATE agent_goal_stages SET state=?,reason=?,evidence=?,version=version+1 WHERE goal=? AND id=? AND version=? AND plan_version=? AND state='RUNNING'")
                  .params(
                      state,
                      observed.reason(),
                      encode(evidence),
                      goal.id(),
                      attempt.stageId(),
                      stage.version(),
                      stage.planVersion())
                  .update()
              != 1) throw new IllegalStateException("Stage CAS failed");
          return true;
        });
  }

  public void state(GoalRepository.Goal goal, Stage expected, String state, String reason) {
    if (!java.util.Set.of("STALE", "TODO", "BLOCKED", "VERIFYING").contains(state))
      throw new IllegalArgumentException("Host transition required");
    if (db.sql(
                "UPDATE agent_goal_stages SET state=?,reason=?,version=version+1 WHERE goal=? AND id=? AND version=? AND plan_version=?")
            .params(
                state,
                reason,
                goal.id(),
                expected.definition().id(),
                expected.version(),
                expected.planVersion())
            .update()
        != 1) throw new IllegalStateException("Stage CAS failed");
  }

  public void attachProof(
      GoalRepository.Goal expected, String stageId, long version, GoalCompletionGate.Proof proof) {
    GoalCompletionGate.validateProof(proof);
    goals.atomic(
        () -> {
          lock(expected);
          var goal = goals.get(expected.projectId(), expected.id());
          if (java.util.Set.of("RUNNING", "COMPLETED", "CANCELLED").contains(goal.status()))
            throw new IllegalStateException("Stop the Goal before attaching stage evidence");
          if (db.sql(
                      "UPDATE agent_goal_stages SET proof=?,state='STALE',version=version+1 WHERE goal=? AND id=? AND version=? AND state NOT IN ('RUNNING','DECOMPOSED')")
                  .params(encode(proof), goal.id(), stageId, version)
                  .update()
              != 1) throw new IllegalStateException("Stage changed");
          return null;
        });
  }

  /** Only after the human reconciles the exact Goal Run; never an automatic startup replay. */
  void reconcile(GoalRepository.Goal goal, String run) {
    db.sql(
            "UPDATE agent_goal_stage_attempts SET status='UNKNOWN',reason='uncertain_run_reconciled',finished_at=? WHERE goal=? AND run=? AND status='RUNNING'")
        .params(clock.instant().toString(), goal.id(), run)
        .update();
    db.sql(
            "UPDATE agent_goal_stages SET state='STALE',reason='uncertain_run_reconciled',version=version+1 WHERE goal=? AND id IN (SELECT stage FROM agent_goal_stage_attempts WHERE goal=? AND run=?) AND state='RUNNING'")
        .params(goal.id(), goal.id(), run)
        .update();
  }
}
