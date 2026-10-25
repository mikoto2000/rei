package dev.mikoto2000.rei.secretary;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Durable admission and budgets around existing executors; no second model/planner loop. */
@Component
@ConditionalOnProperty(name = "rei.secretary.enabled", havingValue = "true")
public final class SecretaryAutonomy implements SecretaryTick, SecretaryContext, AutoCloseable {
  public enum Kind {
    RESEARCH,
    ORGANIZE,
    SUMMARY,
    PREPARE,
    STATUS,
    DRAFT,
    UPDATE_TASK,
    GOAL,
    ROUTINE
  }

  public record Settings(
      String projectId,
      boolean enabled,
      String sessionId,
      int maxLevel,
      boolean autoPrepare,
      Kind autoKind,
      int maxConcurrent,
      int maxSeconds,
      int maxLlmCalls,
      int maxDailyCalls,
      int maxRetries) {}

  public record Request(
      String projectId,
      String taskId,
      long taskRevision,
      String key,
      Kind kind,
      SecretaryTask.Status targetStatus,
      String goalId) {}

  public record Spec(
      Request request,
      String sessionId,
      String objective,
      String fingerprint,
      String policyFingerprint,
      int maxSeconds,
      int maxLlmCalls,
      int maxDailyCalls,
      int maxRetries,
      String proposalId,
      String preparationPermit,
      String preparationRule,
      SecretaryGoalRuntime.Goal goal,
      @com.fasterxml.jackson.annotation.JsonInclude(
              com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
          String externalPermission,
      @com.fasterxml.jackson.annotation.JsonInclude(
              com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
          String privateModelFingerprint) {}

  public record Result(String text, String sha256, String artifactId, String verification) {}

  public record Job(
      String id,
      Spec spec,
      String state,
      long revision,
      String runId,
      int attempts,
      int calls,
      int allocated,
      Instant deadline,
      Instant notBefore,
      Result result,
      String reason,
      String permitId,
      String authorization,
      Instant created,
      Instant updated) {}

  public record History(long sequence, String state, String reason, long revision, Instant at) {}

  public record Status(
      Settings settings, boolean automaticEnabled, int callsReservedToday, List<Job> jobs) {
    public Status {
      jobs = List.copyOf(jobs);
    }
  }

  private static final Set<String> ACTIVE =
      Set.of(
          "DISPATCHING",
          "RUNNING",
          "WAITING",
          "CANCELLING",
          "SUSPENDING",
          "TIMING_OUT",
          "FINALIZING");
  private static final Set<String> TERMINAL =
      Set.of(
          "PREPARED",
          "COMPLETED",
          "FAILED",
          "CANCELLED",
          "TIMED_OUT",
          "BLOCKED",
          "UNCERTAIN",
          "LIMITED");
  private volatile dev.mikoto2000.rei.life.LifePolicy life;

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  void setLifeContext(dev.mikoto2000.rei.life.LifePolicy value) {
    life = value;
  }

  private final SecretaryDatabase database;
  private final SecretaryLedger ledger;
  private final Clock clock;
  private final boolean automatic;
  private ObjectProvider<SecretaryPreparationRuntime> runtimes;
  private ObjectProvider<SecretaryExecutionPolicy> policies;
  private ObjectProvider<SecretaryPermissions> permissions;
  private ObjectProvider<SecretaryDrafts> drafts;
  private ObjectProvider<SecretaryGoalRuntime> goals;
  private ObjectProvider<SecretaryOrchestrator> orchestrators;
  private ObjectProvider<SecretaryReports> reports;
  private final AtomicBoolean busy = new AtomicBoolean();
  private final AtomicBoolean closed = new AtomicBoolean();
  private final ThreadPoolExecutor worker =
      new ThreadPoolExecutor(
          1,
          1,
          0,
          TimeUnit.SECONDS,
          new ArrayBlockingQueue<>(1),
          r -> {
            var t = new Thread(r, "secretary-autonomy");
            t.setDaemon(true);
            return t;
          });

  public SecretaryAutonomy(
      SecretaryDatabase database,
      SecretaryLedger ledger,
      Clock clock,
      @Value("${rei.secretary.autonomy-enabled:false}") boolean automatic) {
    this.database = database;
    this.ledger = ledger;
    this.clock = clock;
    this.automatic = automatic;
  }

  @Autowired
  public void setServices(
      ObjectProvider<SecretaryPreparationRuntime> runtimes,
      ObjectProvider<SecretaryExecutionPolicy> policies,
      ObjectProvider<SecretaryPermissions> permissions,
      ObjectProvider<SecretaryDrafts> drafts,
      ObjectProvider<SecretaryGoalRuntime> goals,
      ObjectProvider<SecretaryOrchestrator> orchestrators,
      ObjectProvider<SecretaryReports> reports) {
    this.runtimes = runtimes;
    this.policies = policies;
    this.permissions = permissions;
    this.drafts = drafts;
    this.goals = goals;
    this.orchestrators = orchestrators;
    this.reports = reports;
  }

  private ObjectProvider<SecretaryPreparationContext> externalContexts;

  @Autowired
  public void setExternalContexts(ObjectProvider<SecretaryPreparationContext> value) {
    externalContexts = value;
  }

  private static boolean external(SecretaryTask task) {
    return "EXTERNAL".equals(task.origin())
        || task.references().stream().anyMatch(r -> r.startsWith("external:"));
  }

  private boolean externalAllowed(Job job, SecretaryTask task) {
    if (job.spec().request().kind() == Kind.UPDATE_TASK || goal(job)) return true;
    if (!external(task) && job.spec().externalPermission() == null) return true;
    var context = optional(externalContexts);
    return context != null && context.allowsExternal(task, job.spec().externalPermission());
  }

  private static <T> T optional(ObjectProvider<T> source) {
    return source == null ? null : source.getIfAvailable();
  }

  private static <T> T required(ObjectProvider<T> source) {
    var value = optional(source);
    if (value == null)
      throw new IllegalStateException("Existing secretary execution capability unavailable");
    return value;
  }

  private static void identifier(String value, int max) {
    if (value == null
        || value.isBlank()
        || value.length() > max
        || value.chars().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException("Invalid secretary execution identifier");
  }

  private static int level(Kind kind) {
    return kind == Kind.GOAL || kind == Kind.ROUTINE
        ? 3
        : kind == Kind.DRAFT || kind == Kind.UPDATE_TASK ? 2 : 1;
  }

  private static boolean goal(Job job) {
    return job.spec().goal() != null;
  }

  private static String encode(Object value) throws java.io.IOException {
    return SecretaryJson.JSON.writeValueAsString(value);
  }

  private static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static String uuid(String value) {
    return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString();
  }

  public Settings settings(String project) {
    identifier(project, 128);
    return database.read(db -> settings(db, project));
  }

  private Settings settings(Connection db, String project)
      throws java.sql.SQLException, java.io.IOException {
    try (var q =
        db.prepareStatement("SELECT record FROM secretary_autonomy_settings WHERE project=?")) {
      q.setString(1, project);
      try (var r = q.executeQuery()) {
        return r.next()
            ? SecretaryJson.JSON.readValue(r.getString(1), Settings.class)
            : new Settings(project, false, "", 1, false, Kind.PREPARE, 1, 300, 8, 32, 1);
      }
    }
  }

  public Settings configure(Settings value) {
    if (value == null) throw new IllegalArgumentException("Settings required");
    identifier(value.projectId(), 128);
    if (value.maxLevel() < 1
        || value.maxLevel() > 3
        || value.autoKind() == null
        || value.autoKind() == Kind.UPDATE_TASK
        || level(value.autoKind()) == 3
        || value.maxConcurrent() < 1
        || value.maxConcurrent() > 2
        || value.maxSeconds() < 10
        || value.maxSeconds() > 3600
        || value.maxLlmCalls() < 0
        || value.maxLlmCalls() > 100
        || value.maxDailyCalls() < 0
        || value.maxDailyCalls() > 10000
        || value.maxRetries() < 0
        || value.maxRetries() > 3
        || level(value.autoKind()) > value.maxLevel())
      throw new IllegalArgumentException("Invalid autonomy limits");
    if (value.enabled()) {
      identifier(value.sessionId(), 256);
      required(runtimes).validate(value.projectId(), value.sessionId());
    }
    return database.transaction(
        db -> {
          try (var q = db.createStatement();
              var r = q.executeQuery("SELECT COUNT(*) FROM secretary_autonomy_settings")) {
            r.next();
            if (r.getInt(1) >= 256 && !configured(db, value.projectId()))
              throw new IllegalStateException("Autonomy Project capacity reached");
          }
          var old = settings(db, value.projectId());
          try (var q =
              db.prepareStatement(
                  "INSERT INTO secretary_autonomy_settings VALUES(?,?,?) ON CONFLICT(project) DO UPDATE SET record=excluded.record,configured=CASE WHEN ? THEN excluded.configured ELSE configured END")) {
            q.setString(1, value.projectId());
            q.setString(2, encode(value));
            q.setLong(3, clock.millis());
            q.setBoolean(4, !old.enabled() && value.enabled());
            q.executeUpdate();
          }
          return value;
        });
  }

  private static boolean configured(Connection db, String project) throws java.sql.SQLException {
    try (var q = db.prepareStatement("SELECT 1 FROM secretary_autonomy_settings WHERE project=?")) {
      q.setString(1, project);
      try (var r = q.executeQuery()) {
        return r.next();
      }
    }
  }

  private SecretaryExecutionPolicy.Decision policy(Kind kind) {
    var source = optional(policies);
    return source == null
        ? new SecretaryExecutionPolicy.Decision(false, "DENY", "unavailable")
        : source.decision(kind.name());
  }

  public Job create(Request request) {
    return create(request, null);
  }

  private Job create(Request request, SecretaryOrchestrator.Suggestion proposal) {
    if (request == null || request.kind() == null || request.taskRevision() < 0)
      throw new IllegalArgumentException("Invalid execution request");
    identifier(request.projectId(), 128);
    identifier(request.taskId(), 128);
    identifier(request.key(), 256);
    if (request.kind() == Kind.UPDATE_TASK
            && (request.targetStatus() == null
                || request.targetStatus() == SecretaryTask.Status.CANDIDATE)
        || request.kind() != Kind.UPDATE_TASK && request.targetStatus() != null)
      throw new IllegalArgumentException("Invalid task update");
    boolean isGoal = level(request.kind()) == 3;
    if (isGoal) identifier(request.goalId(), 128);
    else if (request.goalId() != null)
      throw new IllegalArgumentException("Unexpected Goal reference");
    String fingerprint;
    try {
      fingerprint = hash(encode(request));
    } catch (java.io.IOException error) {
      throw new IllegalArgumentException("Invalid execution request");
    }
    var existing = database.read(db -> receipt(db, request.projectId(), request.key()));
    if (existing != null) {
      if (!existing.spec().fingerprint().equals(fingerprint))
        throw new SecretaryConflictException();
      return existing;
    }
    var settings = settings(request.projectId());
    if (!settings.enabled()) throw new IllegalStateException("Enable Project autonomy first");
    if (level(request.kind()) > settings.maxLevel())
      throw new IllegalArgumentException("Autonomy level exceeds allowed range");
    var task = ledger.get(request.projectId(), request.taskId());
    if (task.revision() != request.taskRevision()) throw new SecretaryConflictException();
    if (!Set.of(SecretaryTask.Status.OPEN, SecretaryTask.Status.WAITING).contains(task.status()))
      throw new IllegalArgumentException("Task is not accepted and active");
    var goal = isGoal ? required(goals).describe(request.projectId(), request.goalId()) : null;
    if (goal != null
        && (goal.remainingCalls() > settings.maxLlmCalls()
            || goal.remainingRuns() > settings.maxRetries() + 1
            || !Set.of("READY", "PAUSED", "FAILED", "BLOCKED", "COMPLETED").contains(goal.state())))
      throw new IllegalArgumentException("Goal is unavailable or exceeds the secretary budget");
    String session = goal == null ? settings.sessionId() : goal.sessionId();
    required(runtimes).validate(request.projectId(), session);
    var authority = policy(request.kind());
    var evidence =
        external(task) && !isGoal && request.kind() != Kind.UPDATE_TASK
            ? required(externalContexts).prepareExternal(task)
            : null;
    var spec =
        new Spec(
            request,
            session,
            evidence == null ? task.title() : evidence.objective(),
            fingerprint,
            authority.fingerprint(),
            settings.maxSeconds(),
            settings.maxLlmCalls(),
            settings.maxDailyCalls(),
            settings.maxRetries(),
            proposal == null ? null : proposal.id(),
            proposal == null ? null : proposal.approvalId(),
            proposal == null ? null : proposal.rule(),
            goal,
            evidence == null ? null : evidence.permission(),
            evidence == null ? null : evidence.modelFingerprint());
    String id = uuid(request.projectId() + ":" + request.key());
    var created =
        database.transaction(
            db -> {
              var repeated = receipt(db, request.projectId(), request.key());
              if (repeated != null) {
                if (!repeated.spec().fingerprint().equals(fingerprint))
                  throw new SecretaryConflictException();
                return repeated;
              }
              try (var q =
                      db.prepareStatement(
                          "SELECT COUNT(*) FROM secretary_jobs WHERE state NOT IN ('PREPARED','COMPLETED','FAILED','CANCELLED','TIMED_OUT','BLOCKED','UNCERTAIN','LIMITED')");
                  var r = q.executeQuery()) {
                r.next();
                if (r.getInt(1) >= 256)
                  throw new IllegalStateException("Secretary execution capacity reached");
              }
              try (var q =
                  db.prepareStatement(
                      "INSERT INTO secretary_jobs(id,project,task,receipt,spec,state,revision,attempts,calls,allocated,deadline,next,reason,created,updated) VALUES(?,?,?,?,?,'READY',0,0,0,0,0,?,'',?,?)")) {
                q.setString(1, id);
                q.setString(2, request.projectId());
                q.setString(3, request.taskId());
                q.setString(4, request.key());
                q.setString(5, encode(spec));
                q.setLong(6, clock.millis());
                q.setLong(7, clock.millis());
                q.setLong(8, clock.millis());
                q.executeUpdate();
              }
              var job = find(db, request.projectId(), id);
              history(db, job);
              return job;
            });
    return authorize(created, authority, false);
  }

  private String canonical(Job job) {
    try {
      return encode(List.of(job.id(), job.spec()));
    } catch (java.io.IOException error) {
      throw new IllegalStateException("Cannot encode scoped execution");
    }
  }

  private boolean preparationPermit(Job job) {
    return job.spec().request().kind() == Kind.DRAFT && job.spec().preparationPermit() != null;
  }

  private Job authorize(Job job, SecretaryExecutionPolicy.Decision authority, boolean consuming) {
    if (level(job.spec().request().kind()) < 2) return job;
    var approvals = optional(permissions);
    if (!goal(job)
        && authority.decision().equals("AUTO_APPROVE")
        && job.permitId() == null
        && !preparationPermit(job)) return job;
    if (approvals == null) return transition(job, "BLOCKED", "approval_owner_unavailable");
    if (preparationPermit(job)) {
      String state =
          approvals.secretaryPreparationStatus(
              job.spec().request().projectId(),
              job.spec().preparationPermit(),
              job.spec().request().taskId(),
              job.spec().preparationRule());
      if (state.equals("APPROVED")) {
        if (!consuming
            || approvals.consumeSecretaryPreparation(
                job.spec().request().projectId(),
                job.spec().preparationPermit(),
                job.spec().request().taskId(),
                job.spec().preparationRule())) return job;
        return transition(job, "BLOCKED", "approval_not_consumable");
      }
      return state.equals("PENDING")
          ? transition(job, "WAITING_APPROVAL", "preparation_approval_pending")
          : transition(job, "BLOCKED", "preparation_approval_" + safe(state));
    }
    String action = job.spec().request().kind().name();
    if (job.permitId() == null) {
      String permit =
          approvals.requestSecretaryAction(
              job.spec().request().projectId(), action, canonical(job));
      return database.transaction(
          db -> {
            var current = find(db, job.spec().request().projectId(), job.id());
            expected(current, job.revision());
            try (var q = db.prepareStatement("UPDATE secretary_jobs SET permit=? WHERE id=?")) {
              q.setString(1, permit);
              q.setString(2, job.id());
              q.executeUpdate();
            }
            return change(db, current, "WAITING_APPROVAL", "action_approval_pending");
          });
    }
    String state =
        approvals.secretaryActionStatus(
            job.spec().request().projectId(), job.permitId(), action, canonical(job));
    if (state.equals("APPROVED")) {
      if (!consuming
          || approvals.consumeSecretaryAction(
              job.spec().request().projectId(), job.permitId(), action, canonical(job))) return job;
      return transition(job, "BLOCKED", "approval_not_consumable");
    }
    return state.equals("PENDING")
        ? transition(job, "WAITING_APPROVAL", "action_approval_pending")
        : transition(job, "BLOCKED", "action_approval_" + safe(state));
  }

  public Job start(String project, String id, long revision) {
    var job = get(project, id);
    expected(job, revision);
    if (Set.of("COMPLETED", "PREPARED", "CANCELLED", "TIMED_OUT", "LIMITED").contains(job.state()))
      return job;
    if (!Set.of("READY", "WAITING_APPROVAL", "RETRY_PENDING").contains(job.state()))
      throw new IllegalStateException("Inspect the saved execution before retrying");
    if (job.state().equals("RETRY_PENDING") && job.notBefore().isAfter(clock.instant())) return job;
    if (life != null && !life.backgroundAllowed(project, job.spec().request().taskId())) return job;
    var settings = settings(project);
    var authority = policy(job.spec().request().kind());
    if (!settings.enabled()
        || !authority.enabled()
        || authority.decision().equals("DENY")
        || level(job.spec().request().kind()) > settings.maxLevel())
      return transition(job, "BLOCKED", "policy_or_project_disabled");
    if (job.spec().proposalId() != null) {
      var owner = optional(orchestrators);
      if (owner == null
          || !Set.of("PROPOSED", "EXECUTION_REQUESTED", "APPROVAL_REQUESTED")
              .contains(owner.suggestion(project, job.spec().proposalId()).status()))
        return transition(job, "BLOCKED", "proposal_withdrawn");
    }
    var task = ledger.get(project, job.spec().request().taskId());
    if (task.revision() != job.spec().request().taskRevision()
        || !Set.of(SecretaryTask.Status.OPEN, SecretaryTask.Status.WAITING).contains(task.status()))
      return transition(job, "BLOCKED", "task_changed");
    if (!externalAllowed(job, task))
      return transition(job, "BLOCKED", "external_model_permission_changed");
    required(runtimes).validate(project, job.spec().sessionId());
    if (job.spec().request().kind() != Kind.UPDATE_TASK && !required(runtimes).available())
      return transition(job, "BLOCKED", "managed_execution_unavailable");
    var authorized = authorized(job, authority) ? job : authorize(job, authority, false);
    if (TERMINAL.contains(authorized.state())) return authorized;
    if (!approvalReady(authorized, authority)) {
      database.transaction(
          db -> {
            try (var q =
                db.prepareStatement(
                    "UPDATE secretary_jobs SET next=? WHERE id=? AND revision=? AND state='WAITING_APPROVAL'")) {
              q.setLong(1, clock.millis() + 60000);
              q.setString(2, id);
              q.setLong(3, authorized.revision());
              q.executeUpdate();
            }
            return null;
          });
      return get(project, id);
    }
    Job claimed =
        database.transaction(
            db -> {
              var current = find(db, project, id);
              expected(current, authorized.revision());
              if (current.deadline() != null && !clock.instant().isBefore(current.deadline()))
                return change(db, current, "TIMED_OUT", "deadline_exhausted");
              if (current.attempts() >= current.spec().maxRetries() + 1)
                return change(db, current, "LIMITED", "attempt_budget_exhausted");
              try (var q =
                  db.prepareStatement(
                      "SELECT COUNT(*),COALESCE(SUM(CASE WHEN project=? THEN 1 ELSE 0 END),0),COALESCE(SUM(CASE WHEN project=? AND task=? THEN 1 ELSE 0 END),0) FROM secretary_jobs WHERE state IN ('DISPATCHING','RUNNING','WAITING','CANCELLING','SUSPENDING','TIMING_OUT','FINALIZING')")) {
                q.setString(1, project);
                q.setString(2, project);
                q.setString(3, current.spec().request().taskId());
                try (var r = q.executeQuery()) {
                  r.next();
                  if (r.getInt(1) >= 2
                      || r.getInt(2) >= settings.maxConcurrent()
                      || r.getInt(3) > 0) return current;
                }
              }
              var started = change(db, current, "DISPATCHING", "");
              try (var q =
                  db.prepareStatement(
                      "UPDATE secretary_jobs SET run=?,attempts=attempts+1,deadline=CASE WHEN deadline=0 THEN ? ELSE deadline END WHERE id=?")) {
                q.setString(1, uuid(id + ":" + (current.attempts() + 1)));
                q.setLong(2, clock.millis() + current.spec().maxSeconds() * 1000L);
                q.setString(3, id);
                q.executeUpdate();
              }
              return find(db, project, started.id());
            });
    if (!claimed.state().equals("DISPATCHING")) return claimed;
    // This marker commits before consuming a one-use approval or calling an executor in another DB.
    var consumed = authorized(claimed, authority) ? claimed : authorize(claimed, authority, true);
    if (!consumed.state().equals("DISPATCHING")) return consumed;
    var admitted =
        database.transaction(
            db -> {
              var current = find(db, project, id);
              if (!current.state().equals("DISPATCHING")
                  || !java.util.Objects.equals(current.runId(), consumed.runId())) return current;
              String proof =
                  level(current.spec().request().kind()) == 1
                      ? "READ_ONLY"
                      : authorized(current, authority)
                          ? current.authorization()
                          : preparationPermit(current)
                              ? "PREPARATION:" + current.spec().preparationPermit()
                              : current.permitId() != null
                                  ? "ACTION:" + current.permitId()
                                  : "POLICY:" + authority.fingerprint();
              try (var q =
                  db.prepareStatement("UPDATE secretary_jobs SET authorization=? WHERE id=?")) {
                q.setString(1, proof);
                q.setString(2, id);
                q.executeUpdate();
              }
              return find(db, project, id);
            });
    if (!admitted.state().equals("DISPATCHING")) return admitted;
    if (life != null && !life.backgroundAllowed(project, admitted.spec().request().taskId()))
      return transition(admitted, "PAUSED", "life_policy");
    try {
      if (goal(admitted)) startGoal(admitted);
      else if (admitted.spec().request().kind() == Kind.UPDATE_TASK) updateTask(admitted);
      else {
        var captured = admitted;
        required(runtimes)
            .start(
                new SecretaryPreparationRuntime.Request(
                    captured.runId(),
                    project,
                    captured.spec().sessionId(),
                    captured.spec().objective(),
                    captured.spec().request().kind().name(),
                    captured.spec().privateModelFingerprint()),
                budget(captured),
                outcome -> completed(project, id, captured.runId(), outcome));
        database.transaction(
            db -> {
              var current = find(db, project, id);
              if (current.state().equals("DISPATCHING") && captured.runId().equals(current.runId()))
                change(db, current, "RUNNING", "");
              return null;
            });
      }
    } catch (RuntimeException failure) {
      var current = get(project, id);
      if (ACTIVE.contains(current.state()))
        transition(current, "UNCERTAIN", "dispatch_result_unconfirmed");
    }
    return get(project, id);
  }

  private boolean approvalReady(Job job, SecretaryExecutionPolicy.Decision authority) {
    if (level(job.spec().request().kind()) < 2 || authorized(job, authority)) return true;
    if (!goal(job)
        && authority.decision().equals("AUTO_APPROVE")
        && job.permitId() == null
        && !preparationPermit(job)) return true;
    var source = optional(permissions);
    if (source == null) return false;
    return preparationPermit(job)
        ? source
            .secretaryPreparationStatus(
                job.spec().request().projectId(),
                job.spec().preparationPermit(),
                job.spec().request().taskId(),
                job.spec().preparationRule())
            .equals("APPROVED")
        : job.permitId() != null
            && source
                .secretaryActionStatus(
                    job.spec().request().projectId(),
                    job.permitId(),
                    job.spec().request().kind().name(),
                    canonical(job))
                .equals("APPROVED");
  }

  private static boolean authorized(Job job, SecretaryExecutionPolicy.Decision authority) {
    if (job.authorization() == null) return false;
    if (level(job.spec().request().kind()) == 1) return job.authorization().equals("READ_ONLY");
    if (job.permitId() != null && job.authorization().equals("ACTION:" + job.permitId()))
      return true;
    if (job.spec().request().kind() == Kind.DRAFT
        && job.spec().preparationPermit() != null
        && job.authorization().equals("PREPARATION:" + job.spec().preparationPermit())) return true;
    return !goal(job)
        && authority.decision().equals("AUTO_APPROVE")
        && job.authorization().equals("POLICY:" + authority.fingerprint());
  }

  private SecretaryPreparationRuntime.CallBudget budget(Job owner) {
    return new SecretaryPreparationRuntime.CallBudget() {
      @Override
      public boolean tryReserve() {
        if (closed.get()) return false;
        if (life != null
            && !life.backgroundAllowed(
                owner.spec().request().projectId(), owner.spec().request().taskId())) return false;
        if (owner.spec().externalPermission() != null) {
          var currentTask =
              ledger.get(owner.spec().request().projectId(), owner.spec().request().taskId());
          if (!externalAllowed(owner, currentTask)) return false;
        }
        String day = day(owner.spec().request().projectId());
        return database.transaction(
            db -> {
              var job = find(db, owner.spec().request().projectId(), owner.id());
              var settings = settings(db, owner.spec().request().projectId());
              if (job == null
                  || !owner.runId().equals(job.runId())
                  || !Set.of("DISPATCHING", "RUNNING", "WAITING").contains(job.state())
                  || !settings.enabled()
                  || level(job.spec().request().kind()) > settings.maxLevel()
                  || job.deadline() == null
                  || !clock.instant().isBefore(job.deadline())
                  || !policy(job.spec().request().kind()).enabled()
                  || policy(job.spec().request().kind()).decision().equals("DENY")
                  || !authorized(job, policy(job.spec().request().kind()))
                  || job.calls() >= Math.min(job.spec().maxLlmCalls(), settings.maxLlmCalls())
                  || daily(db, job.spec().request().projectId(), day)
                      >= Math.min(job.spec().maxDailyCalls(), settings.maxDailyCalls()))
                return false;
              reserveDaily(db, job.spec().request().projectId(), day, 1);
              try (var q =
                  db.prepareStatement("UPDATE secretary_jobs SET calls=calls+1 WHERE id=?")) {
                q.setString(1, job.id());
                q.executeUpdate();
              }
              return true;
            });
      }

      @Override
      public int remaining() {
        var job = get(owner.spec().request().projectId(), owner.id());
        var settings = settings(owner.spec().request().projectId());
        return Math.max(
            0,
            Math.min(
                Math.min(job.spec().maxLlmCalls(), settings.maxLlmCalls()) - job.calls(),
                Math.min(job.spec().maxDailyCalls(), settings.maxDailyCalls())
                    - daily(job.spec().request().projectId())));
      }
    };
  }

  private void completed(
      String project, String id, String run, SecretaryPreparationRuntime.Outcome outcome) {
    var job = get(project, id);
    if (!run.equals(job.runId())
        || !Set.of("DISPATCHING", "RUNNING", "WAITING").contains(job.state())) return;
    if (outcome.status().equals("SUCCESS")) {
      if (outcome.text() == null || outcome.text().isBlank() || outcome.text().length() > 32768) {
        finish(job, "FAILED", "invalid_preparation_result", null);
        return;
      }
      if (!settings(project).enabled()
          || !policy(job.spec().request().kind()).enabled()
          || policy(job.spec().request().kind()).decision().equals("DENY")
          || !authorized(job, policy(job.spec().request().kind()))) {
        finish(job, "BLOCKED", "policy_or_project_disabled", null);
        return;
      }
      if (job.deadline() != null && !clock.instant().isBefore(job.deadline())) {
        finish(job, "TIMED_OUT", "deadline_exhausted", null);
        return;
      }
      if (job.spec().request().kind() == Kind.DRAFT) {
        var finalizing = transition(job, "FINALIZING", "");
        try {
          var artifact = required(drafts).save(project, job.spec().sessionId(), id, outcome.text());
          if (!required(drafts).verify(project, job.spec().sessionId(), artifact)
              || !artifact.sha256().equals(hash(outcome.text())))
            throw new IllegalStateException("Draft verification failed");
          finish(
              finalizing,
              "COMPLETED",
              "draft_verified",
              new Result(
                  outcome.text(), artifact.sha256(), artifact.id(), "artifact_content_sha256"));
        } catch (RuntimeException unconfirmed) {
          finish(get(project, id), "UNCERTAIN", "draft_result_unconfirmed", null);
        }
      } else
        finish(
            job,
            "PREPARED",
            "preparation_saved",
            new Result(outcome.text(), hash(outcome.text()), null, "saved_result_sha256"));
    } else if (outcome.status().equals("CANCELLED"))
      finish(job, "CANCELLED", "runtime_cancelled", null);
    else if (outcome.status().startsWith("WAITING"))
      transition(job, "WAITING", safe(outcome.status()));
    else if (outcome.reason().equals("transient")
        && job.attempts() < job.spec().maxRetries() + 1
        && job.deadline() != null
        && clock.instant().isBefore(job.deadline())
        && job.calls() < job.spec().maxLlmCalls()) {
      var retry = transition(job, "RETRY_PENDING", "transient");
      database.transaction(
          db -> {
            try (var q = db.prepareStatement("UPDATE secretary_jobs SET next=? WHERE id=?")) {
              q.setLong(1, clock.millis() + 60000L * (1L << Math.min(3, retry.attempts() - 1)));
              q.setString(2, id);
              q.executeUpdate();
            }
            return null;
          });
    } else
      finish(
          job,
          job.calls() >= job.spec().maxLlmCalls() ? "LIMITED" : "FAILED",
          safe(outcome.reason()),
          null);
  }

  private void updateTask(Job job) {
    String project = job.spec().request().projectId();
    String destination = destination(project);
    var changed =
        database.transaction(
            db -> {
              var current = find(db, project, job.id());
              if (!current.state().equals("DISPATCHING")
                  || !java.util.Objects.equals(current.runId(), job.runId())) return current;
              var authority = policy(current.spec().request().kind());
              if (!settings(db, project).enabled()
                  || !authority.enabled()
                  || authority.decision().equals("DENY")
                  || !authorized(current, authority))
                return finishWithin(
                    db, current, "BLOCKED", "policy_or_project_disabled", null, destination);
              var task = ledger.getWithin(db, project, current.spec().request().taskId());
              if (task.revision() != current.spec().request().taskRevision())
                return finishWithin(db, current, "BLOCKED", "task_changed", null, destination);
              var finalizing = change(db, current, "FINALIZING", "");
              var refs = new ArrayList<>(task.references());
              refs.add("secretary-execution:" + job.id());
              var updated =
                  ledger.updateWithin(
                      db,
                      project,
                      task.id(),
                      task.revision(),
                      task.title(),
                      task.intent(),
                      current.spec().request().targetStatus(),
                      task.due(),
                      task.priority(),
                      task.tags(),
                      refs);
              var result =
                  new Result(
                      updated.status().name(),
                      hash(updated.id() + ":" + updated.revision() + ":" + updated.status()),
                      null,
                      "ledger_revision_and_execution_reference");
              return finishWithin(
                  db,
                  finalizing,
                  "COMPLETED",
                  "task_state_and_reference_verified",
                  result,
                  destination);
            });
    recordProposal(changed);
  }

  private void startGoal(Job job) {
    var source = required(goals);
    var current = source.describe(job.spec().request().projectId(), job.spec().request().goalId());
    if (!current.fingerprint().equals(job.spec().goal().fingerprint())) {
      finish(job, "BLOCKED", "goal_definition_changed", null);
      return;
    }
    if (job.allocated() == 0) {
      boolean allocated =
          database.transaction(
              db -> {
                var settings = settings(db, job.spec().request().projectId());
                String day = day(job.spec().request().projectId());
                int calls = current.remainingCalls();
                if (calls > Math.min(job.spec().maxLlmCalls(), settings.maxLlmCalls())
                    || daily(db, job.spec().request().projectId(), day) + calls
                        > Math.min(job.spec().maxDailyCalls(), settings.maxDailyCalls()))
                  return false;
                reserveDaily(db, job.spec().request().projectId(), day, calls);
                try (var q =
                    db.prepareStatement(
                        "UPDATE secretary_jobs SET allocated=?,calls=? WHERE id=?")) {
                  q.setInt(1, calls);
                  q.setInt(2, calls);
                  q.setString(3, job.id());
                  q.executeUpdate();
                }
                return true;
              });
      if (!allocated) {
        finish(
            get(job.spec().request().projectId(), job.id()),
            "LIMITED",
            "goal_budget_unavailable",
            null);
        return;
      }
    }
    source.start(
        job.spec().request().projectId(),
        job.spec().request().goalId(),
        current.fingerprint(),
        () -> {
          var fresh = get(job.spec().request().projectId(), job.id());
          var limits = settings(job.spec().request().projectId());
          var authority = policy(fresh.spec().request().kind());
          var observed =
              source.describe(job.spec().request().projectId(), job.spec().request().goalId());
          return Set.of("DISPATCHING", "RUNNING", "WAITING").contains(fresh.state())
              && limits.enabled()
              && limits.maxLevel() >= 3
              && authority.enabled()
              && !authority.decision().equals("DENY")
              && clock.instant().isBefore(fresh.deadline())
              && authorized(fresh, authority)
              && observed.fingerprint().equals(fresh.spec().goal().fingerprint())
              && observed.callsUsed() - fresh.spec().goal().callsUsed()
                  < Math.min(fresh.spec().maxLlmCalls(), limits.maxLlmCalls())
              && observed.attempts() - fresh.spec().goal().attempts() <= limits.maxRetries() + 1;
        });
    var after = source.describe(job.spec().request().projectId(), job.spec().request().goalId());
    database.transaction(
        db -> {
          var fresh = find(db, job.spec().request().projectId(), job.id());
          try (var q = db.prepareStatement("UPDATE secretary_jobs SET run=? WHERE id=?")) {
            q.setString(1, after.runId());
            q.setString(2, job.id());
            q.executeUpdate();
          }
          if (fresh.state().equals("DISPATCHING")) change(db, fresh, "RUNNING", "");
          return null;
        });
    monitor(get(job.spec().request().projectId(), job.id()));
  }

  public Job cancel(String project, String id, long revision) {
    return stop(project, id, revision, false);
  }

  public Job suspend(String project, String id, long revision) {
    return stop(project, id, revision, true);
  }

  private Job stop(String project, String id, long revision, boolean suspend) {
    var job = get(project, id);
    expected(job, revision);
    if (TERMINAL.contains(job.state()) || job.state().equals("FINALIZING")) return job;
    if (!ACTIVE.contains(job.state()))
      return transition(
          job, suspend ? "PAUSED" : "CANCELLED", suspend ? "user_suspended" : "user_cancelled");
    var changed =
        transition(
            job,
            suspend ? "SUSPENDING" : "CANCELLING",
            suspend ? "user_suspended" : "user_cancelled");
    try {
      stopRuntime(changed, !suspend);
    } catch (RuntimeException unavailable) {
      return changed;
    }
    monitor(get(project, id));
    return get(project, id);
  }

  private void stopRuntime(Job job, boolean cancel) {
    String project = job.spec().request().projectId();
    if (goal(job)) {
      String run = required(goals).stopRun(project, job.spec().request().goalId(), cancel);
      database.transaction(
          db -> {
            try (var q =
                db.prepareStatement(
                    "UPDATE secretary_jobs SET run=? WHERE id=? AND state IN ('CANCELLING','SUSPENDING','TIMING_OUT')")) {
              q.setString(1, run);
              q.setString(2, job.id());
              q.executeUpdate();
            }
            return null;
          });
    } else required(runtimes).cancel(project, job.spec().sessionId(), job.runId());
  }

  public Job resume(String project, String id, long revision) {
    var job = get(project, id);
    expected(job, revision);
    if (!job.state().equals("PAUSED"))
      throw new IllegalStateException(
          "Only a confirmed pause can resume; inspect uncertain effects first");
    var ready = transition(job, "READY", "explicit_resume");
    return start(project, id, ready.revision());
  }

  public Job verify(String project, String id, long revision) {
    var job = get(project, id);
    expected(job, revision);
    if (job.spec().request().kind() == Kind.UPDATE_TASK) {
      var task = ledger.get(project, job.spec().request().taskId());
      if (task.status() == job.spec().request().targetStatus()
          && task.references().contains("secretary-execution:" + id)) {
        finish(
            job,
            "COMPLETED",
            "task_state_and_reference_verified",
            new Result(
                task.status().name(),
                hash(task.id() + ":" + task.revision() + ":" + task.status()),
                null,
                "ledger_revision_and_execution_reference"));
        return get(project, id);
      }
    } else if (job.spec().request().kind() == Kind.DRAFT) {
      var source = optional(drafts);
      var artifact = source == null ? null : source.find(project, job.spec().sessionId(), id);
      if (artifact != null && source.verify(project, job.spec().sessionId(), artifact)) {
        finish(
            job,
            "COMPLETED",
            "draft_verified",
            new Result(
                job.result() == null ? "" : job.result().text(),
                artifact.sha256(),
                artifact.id(),
                "artifact_content_sha256"));
        return get(project, id);
      }
    } else if (goal(job)) {
      var observed = required(goals).describe(project, job.spec().request().goalId());
      if (observed.verified() && observed.fingerprint().equals(job.spec().goal().fingerprint())) {
        finish(
            job,
            "COMPLETED",
            "goal_criteria_verified",
            new Result(
                observed.reason(),
                hash(observed.fingerprint() + ":" + observed.reason()),
                null,
                "existing_goal_completion_gate"));
        return get(project, id);
      }
    } else if (job.result() != null && hash(job.result().text()).equals(job.result().sha256()))
      return job;
    return transition(job, "UNCERTAIN", "completion_unverified");
  }

  private void monitor(Job job) {
    String project = job.spec().request().projectId();
    if (!ACTIVE.contains(job.state())) return;
    if (job.state().equals("FINALIZING")) {
      if (job.updated().plusSeconds(60).isBefore(clock.instant()))
        verify(project, job.id(), job.revision());
      return;
    }
    if (!Set.of("CANCELLING", "SUSPENDING", "TIMING_OUT").contains(job.state())
        && (job.deadline() != null && !clock.instant().isBefore(job.deadline())
            || !settings(project).enabled())) {
      var timing =
          transition(
              job,
              "TIMING_OUT",
              settings(project).enabled() ? "deadline_exhausted" : "project_disabled");
      try {
        stopRuntime(timing, true);
      } catch (RuntimeException unavailable) {
        return;
      }
      monitor(get(project, job.id()));
      return;
    }
    if (goal(job) && !Set.of("CANCELLING", "SUSPENDING", "TIMING_OUT").contains(job.state())) {
      var observed = required(goals).describe(project, job.spec().request().goalId());
      if (!observed.fingerprint().equals(job.spec().goal().fingerprint())) {
        stop(project, job.id(), job.revision(), true);
        return;
      }
      if (observed.verified()) {
        verify(project, job.id(), job.revision());
        return;
      }
      if (Set.of("FAILED", "BLOCKED", "CANCELLED", "PAUSED").contains(observed.state())) {
        finish(job, observed.state(), safe(observed.reason()), null);
        return;
      }
      if (observed.state().startsWith("WAITING")) {
        if (!job.state().equals("WAITING")) transition(job, "WAITING", safe(observed.state()));
        return;
      }
      if (observed.runId() != null && !observed.runId().equals(job.runId()))
        database.transaction(
            db -> {
              try (var q = db.prepareStatement("UPDATE secretary_jobs SET run=? WHERE id=?")) {
                q.setString(1, observed.runId());
                q.setString(2, job.id());
                q.executeUpdate();
              }
              return null;
            });
      if (observed.state().equals("RUNNING")
          && (observed.runId() == null
              || !required(runtimes)
                  .inspect(project, job.spec().sessionId(), observed.runId())
                  .active()))
        finish(get(project, job.id()), "UNCERTAIN", "goal_run_unconfirmed", null);
      return;
    }
    if (job.runId() == null) {
      finish(job, "UNCERTAIN", "control_target_unconfirmed", null);
      return;
    }
    var observed = required(runtimes).inspect(project, job.spec().sessionId(), job.runId());
    if (observed.active()) return;
    if (job.state().equals("SUSPENDING") && goal(job)) {
      var result = required(goals).describe(project, job.spec().request().goalId());
      if (result.verified()) verify(project, job.id(), job.revision());
      else finish(job, "UNCERTAIN", "goal_interruption_effects_unconfirmed", null);
    } else if (job.state().equals("SUSPENDING")) finish(job, "PAUSED", "user_suspended", null);
    else if (job.state().equals("CANCELLING")) finish(job, "CANCELLED", "user_cancelled", null);
    else if (job.state().equals("TIMING_OUT")) finish(job, "TIMED_OUT", job.reason(), null);
    else finish(job, "UNCERTAIN", "run_result_unconfirmed", null);
  }

  public void check() {
    for (var job : query(null, "ACTIVE", 32)) {
      if (Thread.currentThread().isInterrupted()) return;
      try {
        monitor(job);
      } catch (RuntimeException unavailable) {
        org.slf4j.LoggerFactory.getLogger(getClass())
            .warn(
                "Secretary execution observation unavailable: {}",
                unavailable.getClass().getSimpleName());
      }
    }
    for (var job : query(null, "READY", 8)) {
      if (Thread.currentThread().isInterrupted()) return;
      try {
        start(job.spec().request().projectId(), job.id(), job.revision());
      } catch (SecretaryConflictException changed) {
        /* Another owner changed the admission. */
      } catch (RuntimeException unavailable) {
        org.slf4j.LoggerFactory.getLogger(getClass())
            .warn(
                "Secretary execution admission unavailable: {}",
                unavailable.getClass().getSimpleName());
      }
    }
    intake();
  }

  private void intake() {
    // Populated by the optional proposal owner; no dependency on external event text or a model.
    var proposals = optional(orchestrators);
    if (proposals == null) return;
    var projects =
        database.read(
            db -> {
              var values = new ArrayList<String>();
              try (var q = db.createStatement();
                  var r =
                      q.executeQuery(
                          "SELECT project FROM secretary_autonomy_settings ORDER BY project LIMIT 256")) {
                while (r.next()) values.add(r.getString(1));
              }
              return values;
            });
    int projectCursor = Math.toIntExact(database.read(db -> cursor(db, "autonomy:projects")));
    for (int index = 0; index < Math.min(16, projects.size()); index++) {
      var project = projects.get(Math.floorMod(projectCursor + index, projects.size()));
      var settings = settings(project);
      if (!settings.enabled()) continue;
      int offset =
          Math.toIntExact(database.read(db -> cursor(db, "autonomy:proposals:" + project)));
      var batch = proposals.executionCandidates(project, offset, 32);
      database.transaction(
          db -> {
            cursor(db, "autonomy:proposals:" + project, batch.size() < 32 ? 0 : offset + 32L);
            return null;
          });
      for (var proposal : batch) {
        boolean explicit =
            Set.of("EXECUTION_REQUESTED", "APPROVAL_REQUESTED").contains(proposal.status());
        if (!explicit
            && !(automatic && settings.autoPrepare() && proposal.status().equals("PROPOSED")))
          continue;
        long configured =
            database.read(
                db -> {
                  try (var q =
                      db.prepareStatement(
                          "SELECT configured FROM secretary_autonomy_settings WHERE project=?")) {
                    q.setString(1, project);
                    try (var r = q.executeQuery()) {
                      return r.next() ? r.getLong(1) : Long.MAX_VALUE;
                    }
                  }
                });
        if (explicit && proposal.updated().toEpochMilli() < configured) continue;
        try {
          var kind =
              proposal.status().equals("APPROVAL_REQUESTED") ? Kind.DRAFT : settings.autoKind();
          create(
              new Request(
                  project,
                  proposal.taskId(),
                  proposal.taskRevision(),
                  "proposal:" + proposal.taskId() + ":" + proposal.taskRevision() + ":" + kind,
                  kind,
                  null,
                  null),
              proposal);
        } catch (RuntimeException unavailable) {
          /* Stale, muted or unauthorized proposals do not become executable. */
        }
      }
    }
    database.transaction(
        db -> {
          cursor(
              db,
              "autonomy:projects",
              projects.isEmpty() ? 0 : Math.floorMod(projectCursor + 16, projects.size()));
          return null;
        });
  }

  private static long cursor(Connection db, String key) throws java.sql.SQLException {
    try (var q = db.prepareStatement("SELECT value FROM secretary_followup_state WHERE key=?")) {
      q.setString(1, key);
      try (var r = q.executeQuery()) {
        return r.next() ? r.getLong(1) : 0;
      }
    }
  }

  private static void cursor(Connection db, String key, long value) throws java.sql.SQLException {
    try (var q =
        db.prepareStatement(
            "INSERT INTO secretary_followup_state VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
      q.setString(1, key);
      q.setLong(2, value);
      q.executeUpdate();
    }
  }

  public Job get(String project, String id) {
    identifier(project, 128);
    identifier(id, 128);
    var value = database.read(db -> find(db, project, id));
    if (value == null) throw new IllegalArgumentException("Execution not found in this Project");
    return value;
  }

  public List<Job> list(String project, int limit) {
    identifier(project, 128);
    bounded(limit);
    return query(project, "LIST", limit);
  }

  private List<Job> query(String project, String mode, int limit) {
    return database.read(
        db -> {
          var values = new ArrayList<Job>();
          var statement =
              switch (mode) {
                case "ACTIVE" ->
                    db.prepareStatement(
                        "SELECT * FROM secretary_jobs WHERE state IN ('DISPATCHING','RUNNING','WAITING','CANCELLING','SUSPENDING','TIMING_OUT','FINALIZING') ORDER BY updated,id LIMIT ?");
                case "READY" ->
                    db.prepareStatement(
                        "SELECT * FROM secretary_jobs WHERE state IN ('RETRY_PENDING','READY','WAITING_APPROVAL') AND next<=? ORDER BY CASE WHEN state='RETRY_PENDING' THEN 0 WHEN state='READY' THEN 1 ELSE 2 END,next,created,id LIMIT ?");
                case "LIST" ->
                    db.prepareStatement(
                        "SELECT * FROM secretary_jobs WHERE project=? ORDER BY created DESC,id LIMIT ?");
                default -> throw new IllegalArgumentException("Unknown execution query");
              };
          try (var q = statement) {
            int index = 1;
            if (mode.equals("LIST")) q.setString(index++, project);
            else if (mode.equals("READY")) q.setLong(index++, clock.millis());
            q.setInt(index, limit);
            try (var r = q.executeQuery()) {
              while (r.next()) values.add(row(r));
            }
          }
          return List.copyOf(values);
        });
  }

  public List<History> history(String project, String id, int limit) {
    get(project, id);
    bounded(limit);
    return database.read(
        db -> {
          var values = new ArrayList<History>();
          try (var q =
              db.prepareStatement(
                  "SELECT sequence,state,reason,revision,at FROM secretary_job_history WHERE project=? AND job=? ORDER BY sequence DESC LIMIT ?")) {
            q.setString(1, project);
            q.setString(2, id);
            q.setInt(3, limit);
            try (var r = q.executeQuery()) {
              while (r.next())
                values.add(
                    new History(
                        r.getLong(1),
                        r.getString(2),
                        r.getString(3),
                        r.getLong(4),
                        Instant.ofEpochMilli(r.getLong(5))));
            }
          }
          return List.copyOf(values);
        });
  }

  public Status status(String project) {
    return new Status(settings(project), automatic, daily(project), list(project, 20));
  }

  @Override
  public List<Item> context(String project, LocalDate date, ZoneId zone) {
    identifier(project, 128);
    long from = date.atStartOfDay(zone).toInstant().toEpochMilli();
    long until = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
    return database.read(
        db -> {
          var values = new ArrayList<Item>();
          try (var q =
              db.prepareStatement(
                  "SELECT * FROM secretary_jobs WHERE project=? AND updated>=? AND updated<? ORDER BY updated DESC,id LIMIT 256")) {
            q.setString(1, project);
            q.setLong(2, from);
            q.setLong(3, until);
            try (var r = q.executeQuery()) {
              while (r.next()) {
                var job = row(r);
                String detail =
                    job.spec().privateModelFingerprint() != null
                        ? "Private draft retained in the authenticated execution result."
                        : job.result() == null ? job.reason() : job.result().text();
                if (detail.length() > 512) detail = detail.substring(0, 512) + "…";
                values.add(
                    new Item(
                        "secretary-execution",
                        job.id(),
                        job.spec().request().kind()
                            + ": "
                            + (job.spec().privateModelFingerprint() != null
                                ? "Private external preparation"
                                : job.spec().objective()),
                        job.state(),
                        detail,
                        Reliability.PROPOSAL,
                        List.of("secretary-job:" + job.id())));
              }
            }
          }
          return List.copyOf(values);
        });
  }

  private static void bounded(int limit) {
    if (limit < 1 || limit > 1000) throw new IllegalArgumentException("Invalid limit");
  }

  private static Job row(ResultSet r) throws java.sql.SQLException, java.io.IOException {
    var spec = SecretaryJson.JSON.readValue(r.getString("spec"), Spec.class);
    if (!r.getString("project").equals(spec.request().projectId())
        || !r.getString("task").equals(spec.request().taskId()))
      throw new IllegalStateException("Execution ownership corruption");
    long deadline = r.getLong("deadline");
    String result = r.getString("result");
    return new Job(
        r.getString("id"),
        spec,
        r.getString("state"),
        r.getLong("revision"),
        r.getString("run"),
        r.getInt("attempts"),
        r.getInt("calls"),
        r.getInt("allocated"),
        deadline == 0 ? null : Instant.ofEpochMilli(deadline),
        Instant.ofEpochMilli(r.getLong("next")),
        result == null ? null : SecretaryJson.JSON.readValue(result, Result.class),
        r.getString("reason"),
        r.getString("permit"),
        r.getString("authorization"),
        Instant.ofEpochMilli(r.getLong("created")),
        Instant.ofEpochMilli(r.getLong("updated")));
  }

  private static Job find(Connection db, String project, String id)
      throws java.sql.SQLException, java.io.IOException {
    try (var q = db.prepareStatement("SELECT * FROM secretary_jobs WHERE project=? AND id=?")) {
      q.setString(1, project);
      q.setString(2, id);
      try (var r = q.executeQuery()) {
        return r.next() ? row(r) : null;
      }
    }
  }

  private static Job receipt(Connection db, String project, String receipt)
      throws java.sql.SQLException, java.io.IOException {
    try (var q =
        db.prepareStatement("SELECT * FROM secretary_jobs WHERE project=? AND receipt=?")) {
      q.setString(1, project);
      q.setString(2, receipt);
      try (var r = q.executeQuery()) {
        return r.next() ? row(r) : null;
      }
    }
  }

  private static void expected(Job job, long revision) {
    if (job == null || revision < 0 || job.revision() != revision)
      throw new SecretaryConflictException();
  }

  private Job change(Connection db, Job job, String state, String reason)
      throws java.sql.SQLException, java.io.IOException {
    if (job.state().equals(state) && job.reason().equals(reason))
      return find(db, job.spec().request().projectId(), job.id());
    try (var q =
        db.prepareStatement(
            "UPDATE secretary_jobs SET state=?,reason=?,revision=revision+1,updated=? WHERE id=? AND revision=?")) {
      q.setString(1, state);
      q.setString(2, safe(reason));
      q.setLong(3, clock.millis());
      q.setString(4, job.id());
      q.setLong(5, job.revision());
      if (q.executeUpdate() != 1) throw new SecretaryConflictException();
    }
    var changed = find(db, job.spec().request().projectId(), job.id());
    history(db, changed);
    if (TERMINAL.contains(state) || Set.of("PAUSED", "WAITING_APPROVAL").contains(state)) {
      String permit =
          changed.permitId() == null ? changed.spec().preparationPermit() : changed.permitId();
      String message =
          state
              + ": /secretary work-show "
              + changed.id()
              + (state.equals("WAITING_APPROVAL") && permit != null
                  ? "; /approval approve " + permit
                  : "");
      SecretaryOrchestrator.queue(
          db,
          new SecretaryNotifications.Notice(
              uuid("execution-result:" + job.id() + ":" + changed.revision()),
              job.spec().request().projectId(),
              null,
              "EXECUTION_RESULT",
              message,
              3,
              destination(job.spec().request().projectId()),
              clock.instant()));
    }
    return changed;
  }

  private Job transition(Job job, String state, String reason) {
    return database.transaction(
        db -> {
          var current = find(db, job.spec().request().projectId(), job.id());
          expected(current, job.revision());
          return change(db, current, state, reason);
        });
  }

  private static void history(Connection db, Job job) throws java.sql.SQLException {
    try (var q =
        db.prepareStatement(
            "INSERT INTO secretary_job_history(project,job,state,reason,revision,at) VALUES(?,?,?,?,?,?)")) {
      q.setString(1, job.spec().request().projectId());
      q.setString(2, job.id());
      q.setString(3, job.state());
      q.setString(4, job.reason());
      q.setLong(5, job.revision());
      q.setLong(6, job.updated().toEpochMilli());
      q.executeUpdate();
    }
  }

  private void finish(Job job, String state, String reason, Result result) {
    String destination = destination(job.spec().request().projectId());
    var changed =
        database.transaction(
            db -> {
              var current = find(db, job.spec().request().projectId(), job.id());
              if (current == null
                  || !java.util.Objects.equals(current.runId(), job.runId())
                  || !ACTIVE.contains(current.state()) && !current.state().equals("UNCERTAIN"))
                return null;
              return finishWithin(db, current, state, reason, result, destination);
            });
    recordProposal(changed);
  }

  private Job finishWithin(
      Connection db, Job job, String state, String reason, Result result, String destination)
      throws java.sql.SQLException, java.io.IOException {
    if (result != null)
      try (var q = db.prepareStatement("UPDATE secretary_jobs SET result=? WHERE id=?")) {
        q.setString(1, encode(result));
        q.setString(2, job.id());
        q.executeUpdate();
      }
    var terminal = change(db, job, state, reason);

    return terminal;
  }

  private void recordProposal(Job job) {
    if (job != null && TERMINAL.contains(job.state())) {
      var proposals = optional(orchestrators);
      if (proposals != null && job.spec().proposalId() != null)
        proposals.executionResult(
            job.spec().request().projectId(),
            job.spec().proposalId(),
            job.spec().request().taskRevision(),
            job.id(),
            job.state());
    }
  }

  private String destination(String project) {
    var source = optional(reports);
    return source == null ? "INBOX" : source.settings(project).destination();
  }

  private String day(String project) {
    var source = optional(reports);
    var zone = source == null ? clock.getZone() : ZoneId.of(source.settings(project).zone());
    return clock.instant().atZone(zone).toLocalDate().toString();
  }

  private int daily(String project) {
    identifier(project, 128);
    String day = day(project);
    return database.read(db -> daily(db, project, day));
  }

  private static int daily(Connection db, String project, String day) throws java.sql.SQLException {
    try (var q =
        db.prepareStatement("SELECT calls FROM secretary_model_budget WHERE project=? AND day=?")) {
      q.setString(1, project);
      q.setString(2, day);
      try (var r = q.executeQuery()) {
        return r.next() ? r.getInt(1) : 0;
      }
    }
  }

  private static void reserveDaily(Connection db, String project, String day, int calls)
      throws java.sql.SQLException {
    try (var q =
        db.prepareStatement(
            "INSERT INTO secretary_model_budget VALUES(?,?,?) ON CONFLICT(project,day) DO UPDATE SET calls=calls+excluded.calls")) {
      q.setString(1, project);
      q.setString(2, day);
      q.setInt(3, calls);
      q.executeUpdate();
    }
  }

  private static String safe(String value) {
    return value != null && value.matches("[A-Za-z0-9_:.-]{0,100}") ? value : "execution_failed";
  }

  @Override
  public void tick() {
    if (closed.get() || !busy.compareAndSet(false, true)) return;
    try {
      worker.execute(
          () -> {
            try {
              check();
            } catch (RuntimeException unavailable) {
              org.slf4j.LoggerFactory.getLogger(getClass())
                  .warn(
                      "Secretary autonomy unavailable: {}", unavailable.getClass().getSimpleName());
            } finally {
              busy.set(false);
            }
          });
    } catch (RejectedExecutionException rejected) {
      busy.set(false);
    }
  }

  @Override
  @jakarta.annotation.PreDestroy
  public void close() {
    closed.set(true);
    worker.shutdownNow();
    try {
      worker.awaitTermination(5, TimeUnit.SECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }
}
