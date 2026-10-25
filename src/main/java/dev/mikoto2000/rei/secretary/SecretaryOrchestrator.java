package dev.mikoto2000.rei.secretary;

import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
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

/**
 * Bounded local rule evaluation and interruption control. Complex execution has a separate owner.
 */
@Component
@ConditionalOnProperty(name = "rei.secretary.enabled", havingValue = "true")
public final class SecretaryOrchestrator
    implements SecretaryTick, SecretaryInterruptions, SecretaryContext, AutoCloseable {
  public record Settings(
      String projectId,
      boolean enabled,
      boolean stopped,
      Instant snoozedUntil,
      long minimumSeconds,
      long repeatSeconds) {}

  public record Suggestion(
      String id,
      String projectId,
      String taskId,
      String rule,
      String status,
      int priority,
      long revision,
      long taskRevision,
      Instant created,
      Instant updated,
      Instant notBefore,
      String reason,
      String approvalId) {}

  public record History(long sequence, String action, long revision, Instant at, String detail) {}

  public record Preparation(
      Suggestion suggestion,
      Integer minutes,
      String load,
      SecretaryContext.Reliability certainty,
      long availableMinutes,
      boolean feasible,
      Instant expires,
      SecretaryExecutionMode mode) {}

  public List<Preparation> preparation(String project) {
    required(project);
    long available = life == null ? 0 : life.availableMinutes();
    var zone = ZoneId.of(followups.getObject().settings(project).zone());
    var values = new ArrayList<Preparation>();
    for (var proposal : suggestions(project, 1000)) {
      if (!proposal.status().equals("PROPOSED")
          || !proposal.updated().plusSeconds(7 * 86400L).isAfter(clock.instant())) continue;
      var task = ledger.get(project, proposal.taskId());
      if (task.revision() != proposal.taskRevision() || !SecretaryPreparation.ready(task, ledger))
        continue;
      var minutes = SecretaryPreparation.minutes(task);
      values.add(
          new Preparation(
              proposal,
              minutes,
              SecretaryPreparation.load(task),
              SecretaryContext.Reliability.USER_REGISTERED,
              available,
              minutes != null && minutes <= Math.min(60, available / 2),
              proposal.updated().plusSeconds(7 * 86400L),
              SecretaryExecutionMode.PROPOSAL_ONLY));
    }
    values.sort(
        java.util.Comparator.comparing(
            p ->
                SecretaryPreparation.prepareBy(
                    ledger.get(project, p.suggestion().taskId()), zone)));
    return values.stream().limit(3).toList();
  }

  @Override
  public List<SecretaryContext.Item> context(String project, LocalDate date, ZoneId zone) {
    return preparation(project).stream()
        .map(
            p ->
                new SecretaryContext.Item(
                    "secretary-suggestion",
                    p.suggestion().id(),
                    p.suggestion().reason(),
                    "PROPOSED",
                    "Optional proposal; prep-minutes="
                        + p.minutes()
                        + "; load="
                        + p.load()
                        + "; feasible-now="
                        + p.feasible()
                        + "; mode="
                        + p.mode()
                        + "; expires="
                        + p.expires(),
                    SecretaryContext.Reliability.PROPOSAL,
                    List.of(
                        "secretary-task:" + p.suggestion().taskId(),
                        "secretary-suggestion:" + p.suggestion().id())))
        .toList();
  }

  private void refresh(String project) {
    database.transaction(
        db -> {
          var values = new ArrayList<Suggestion>();
          try (var q =
              db.prepareStatement(
                  "SELECT record FROM secretary_suggestions WHERE project=? AND status IN ('PROPOSED','EXECUTION_REQUESTED','APPROVAL_REQUESTED','DEFERRED') ORDER BY updated,id LIMIT 256")) {
            q.setString(1, project);
            try (var r = q.executeQuery()) {
              while (r.next())
                values.add(SecretaryJson.JSON.readValue(r.getString(1), Suggestion.class));
            }
          }
          for (var value : values) {
            String status = null;
            try {
              var task = ledger.getWithin(db, project, value.taskId());
              if (task.revision() != value.taskRevision()
                  || task.status() == SecretaryTask.Status.DONE
                  || task.status() == SecretaryTask.Status.CANCELLED) status = "INVALIDATED";
            } catch (IllegalArgumentException missing) {
              status = "INVALIDATED";
            }
            var lifetime =
                value.status().equals("DEFERRED") && value.notBefore().isAfter(value.updated())
                    ? value.notBefore()
                    : value.updated();
            if (status == null && !lifetime.plusSeconds(7 * 86400L).isAfter(clock.instant()))
              status = "EXPIRED";
            if (status != null) {
              var changed =
                  new Suggestion(
                      value.id(),
                      project,
                      value.taskId(),
                      value.rule(),
                      status,
                      value.priority(),
                      value.revision() + 1,
                      value.taskRevision(),
                      value.created(),
                      clock.instant(),
                      value.notBefore(),
                      value.reason(),
                      value.approvalId());
              save(db, changed);
              history(db, changed, status, "source revision or lifetime changed");
            }
          }
          return null;
        });
  }

  public enum Feedback {
    EXECUTE,
    LATER,
    DISMISS,
    NEVER,
    ALLOW_AUTO
  }

  public record Status(
      Settings settings,
      boolean backgroundEnabled,
      SecretaryActivity.Snapshot activity,
      List<Suggestion> suggestions,
      long held) {
    public Status {
      suggestions = List.copyOf(suggestions);
    }
  }

  private volatile dev.mikoto2000.rei.life.LifePolicy life;

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  void setLifeContext(dev.mikoto2000.rei.life.LifePolicy value) {
    life = value;
  }

  private final SecretaryDatabase database;
  private final SecretaryLedger ledger;
  private final ObjectProvider<SecretaryReports> followups;
  private final Clock clock;
  private final boolean enabled;
  private List<SecretaryActivity> activities = List.of();
  private SecretaryPermissions permissions;
  private final AtomicBoolean busy = new AtomicBoolean();
  private final ThreadPoolExecutor worker =
      new ThreadPoolExecutor(
          1,
          1,
          0,
          TimeUnit.SECONDS,
          new ArrayBlockingQueue<>(1),
          r -> {
            var t = new Thread(r, "secretary-orchestrator");
            t.setDaemon(true);
            return t;
          });

  @Autowired
  public SecretaryOrchestrator(
      SecretaryDatabase database,
      SecretaryLedger ledger,
      ObjectProvider<SecretaryReports> followups,
      Clock clock,
      @Value("${rei.secretary.orchestrator-enabled:false}") boolean enabled) {
    this.database = database;
    this.ledger = ledger;
    this.followups = followups;
    this.clock = clock;
    this.enabled = enabled;
  }

  @Autowired
  public void setActivitySources(ObjectProvider<SecretaryActivity> sources) {
    activities = sources.orderedStream().limit(8).toList();
  }

  void setActivities(List<SecretaryActivity> sources) {
    activities = List.copyOf(sources);
  }

  @Autowired
  public void setPermissions(ObjectProvider<SecretaryPermissions> sources) {
    permissions = sources.getIfAvailable();
  }

  public Settings settings(String project) {
    required(project);
    return database.read(
        db -> {
          try (var q =
              db.prepareStatement("SELECT record FROM secretary_orchestration WHERE project=?")) {
            q.setString(1, project);
            try (var r = q.executeQuery()) {
              return r.next()
                  ? SecretaryJson.JSON.readValue(r.getString(1), Settings.class)
                  : new Settings(project, false, false, null, 900, 86400);
            }
          }
        });
  }

  public Settings configure(Settings value) {
    if (value == null) throw new IllegalArgumentException("Settings required");
    required(value.projectId());
    if (value.minimumSeconds() < 60
        || value.minimumSeconds() > 86400
        || value.repeatSeconds() < 60
        || value.repeatSeconds() > 2592000
        || value.snoozedUntil() != null
            && value.snoozedUntil().isAfter(clock.instant().plusSeconds(2592000)))
      throw new IllegalArgumentException("Invalid interruption settings");
    return database.transaction(
        db -> {
          try (var q = db.createStatement();
              var r = q.executeQuery("SELECT COUNT(*) FROM secretary_orchestration")) {
            r.next();
            if (r.getInt(1) >= 256 && !hasSettings(db, value.projectId()))
              throw new IllegalStateException("Secretary project capacity reached");
          }
          try (var q =
              db.prepareStatement(
                  "INSERT INTO secretary_orchestration VALUES(?,?) ON CONFLICT(project) DO UPDATE SET record=excluded.record")) {
            q.setString(1, value.projectId());
            q.setString(2, SecretaryJson.JSON.writeValueAsString(value));
            q.executeUpdate();
          }
          return value;
        });
  }

  private static boolean hasSettings(Connection db, String project) throws java.sql.SQLException {
    try (var q = db.prepareStatement("SELECT 1 FROM secretary_orchestration WHERE project=?")) {
      q.setString(1, project);
      try (var r = q.executeQuery()) {
        return r.next();
      }
    }
  }

  public List<Suggestion> evaluate(String project) {
    var settings = settings(project);
    if (!settings.enabled()) return List.of();
    refresh(project);
    int offset = Math.toIntExact(database.read(db -> state(db, "proposal:cursor:" + project)));
    var tasks = ledger.list(project, offset, 64);
    database.transaction(
        db -> {
          state(db, "proposal:cursor:" + project, tasks.size() < 64 ? 0 : offset + 64L);
          return null;
        });
    var routing = followups.getObject().settings(project);
    var report = followups.getObject().report(project, null, false);
    var date = clock.instant().atZone(ZoneId.of(routing.zone())).toLocalDate();
    var orderedTasks =
        tasks.stream().sorted(SecretaryPreparation.order(ZoneId.of(routing.zone()))).toList();
    int slots =
        Math.max(
            0,
            3
                - database.<Integer>read(
                    db -> {
                      try (var q =
                          db.prepareStatement(
                              "SELECT COUNT(*) FROM secretary_suggestions WHERE project=? AND status IN ('PROPOSED','EXECUTION_REQUESTED','APPROVAL_REQUESTED')")) {
                        q.setString(1, project);
                        try (var r = q.executeQuery()) {
                          r.next();
                          return r.getInt(1);
                        }
                      }
                    }));
    var candidates = new ArrayList<Suggestion>();
    for (var task : orderedTasks) {
      if (candidates.size() >= slots) break;
      if (Thread.currentThread().isInterrupted()) break;
      if (task.status() != SecretaryTask.Status.OPEN
          && task.status() != SecretaryTask.Status.WAITING) continue;
      if (task.status() == SecretaryTask.Status.OPEN && !SecretaryPreparation.ready(task, ledger))
        continue;
      String rule = rule(task, date, report.context());
      if (rule == null) continue;
      var saved = propose(settings, routing, task, rule);
      if (saved != null) candidates.add(saved);
    }
    candidates.sort(Comparator.comparingInt(Suggestion::priority).thenComparing(Suggestion::id));
    return List.copyOf(candidates);
  }

  private String rule(SecretaryTask task, LocalDate date, List<SecretaryContext.Item> context) {
    if (task.due() != null && task.due().isBefore(date)) return "OVERDUE";
    if (task.due() != null && !task.due().isAfter(date.plusDays(1))) return "DUE";
    if (task.due() != null
        && SecretaryPreparation.minutes(task) != null
        && !SecretaryPreparation.prepareBy(
                task, ZoneId.of(followups.getObject().settings(task.projectId()).zone()))
            .isAfter(clock.instant().plusSeconds(86400))) return "PREPARATION";
    if (task.status() == SecretaryTask.Status.WAITING)
      return task.updated().isBefore(clock.instant().minusSeconds(86400)) ? "WAITING" : null;
    var predecessors = task.references().stream().filter(r -> r.startsWith("after:")).toList();
    if (!predecessors.isEmpty()
        && predecessors.stream()
            .allMatch(
                reference -> {
                  try {
                    return ledger.get(task.projectId(), reference.substring(6)).status()
                        == SecretaryTask.Status.DONE;
                  } catch (IllegalArgumentException missing) {
                    return false;
                  }
                })) return "NEXT_STEP";
    if (task.priority() <= 2) return "IMPORTANT";
    long age = Duration.between(task.updated(), clock.instant()).getSeconds();
    if (age >= 14 * 86400L) return "NEGLECTED";
    if (age >= 7 * 86400L) return "STALLED";
    if (context.stream()
        .anyMatch(
            i ->
                task.references().contains(i.source() + ":" + i.id())
                    && Set.of("NEXT_ACTION", "CURRENT_WORK", "OPEN", "READY").contains(i.status())))
      return "CONTINUATION";
    return null;
  }

  private Suggestion propose(
      Settings settings, SecretaryFollowups.Settings routing, SecretaryTask task, String rule) {
    String id = uuid(settings.projectId() + ":" + task.id() + ":" + rule);
    return database.transaction(
        db -> {
          var old = find(db, settings.projectId(), id);
          try (var q =
              db.prepareStatement(
                  "SELECT COUNT(*) FROM secretary_suggestions WHERE project=? AND status IN ('PROPOSED','EXECUTION_REQUESTED','APPROVAL_REQUESTED')")) {
            q.setString(1, task.projectId());
            try (var r = q.executeQuery()) {
              r.next();
              if (r.getInt(1) >= 3) return null;
            }
          }
          try (var q =
              db.prepareStatement(
                  "SELECT 1 FROM secretary_suggestions WHERE project=? AND task=? AND (status IN ('MUTED','EXECUTION_REQUESTED','APPROVAL_REQUESTED','PROPOSED') OR (status IN ('DEFERRED','DISMISSED') AND next>?)) LIMIT 1")) {
            q.setString(1, task.projectId());
            q.setString(2, task.id());
            q.setLong(3, clock.millis());
            try (var r = q.executeQuery()) {
              if (r.next()) return null;
            }
          }
          if (old != null
              && old.taskRevision() == task.revision()
              && Set.of("EXPIRED", "INVALIDATED").contains(old.status())) return null;
          if (old != null
              && (Set.of("MUTED", "EXECUTION_REQUESTED", "APPROVAL_REQUESTED")
                      .contains(old.status())
                  || old.taskRevision() == task.revision()
                      && old.notBefore().isAfter(clock.instant()))) return null;
          var now = clock.instant();
          int priority = rule.equals("OVERDUE") ? 1 : task.priority();
          var suggestion =
              new Suggestion(
                  id,
                  task.projectId(),
                  task.id(),
                  rule,
                  "PROPOSED",
                  priority,
                  old == null ? 0 : old.revision() + 1,
                  task.revision(),
                  old == null ? now : old.created(),
                  now,
                  now.plusSeconds(settings.repeatSeconds()),
                  rule + ": " + task.title(),
                  null);
          save(db, suggestion);
          history(db, suggestion, "PROPOSED", rule);
          if (routing.enabled() && routing.notifications()) {
            queue(
                db,
                new SecretaryNotifications.Notice(
                    uuid(id + ":" + suggestion.revision()),
                    task.projectId(),
                    task.id(),
                    "SUGGESTION",
                    suggestion.reason() + "; /secretary suggestion " + id,
                    priority,
                    routing.destination(),
                    now));
          }
          return suggestion;
        });
  }

  public Suggestion feedback(
      String project, String id, long revision, Feedback reaction, long delaySeconds) {
    required(project);
    required(id);
    refresh(project);
    if (reaction == null || revision < 0 || delaySeconds < 0 || delaySeconds > 2592000)
      throw new IllegalArgumentException("Invalid feedback");
    if (reaction == Feedback.ALLOW_AUTO && permissions == null)
      throw new IllegalStateException("Existing approval owner unavailable");
    // Validate before requesting approval; the request cannot grant a permission by itself.
    var before = suggestion(project, id);
    if (Set.of("EXPIRED", "INVALIDATED").contains(before.status()))
      throw new SecretaryConflictException();
    if (before.revision() != revision) throw new SecretaryConflictException();
    if (reaction == Feedback.EXECUTE || reaction == Feedback.ALLOW_AUTO) {
      var task = ledger.get(project, before.taskId());
      if (task.revision() != before.taskRevision() || task.status() != SecretaryTask.Status.OPEN)
        throw new SecretaryConflictException();
    }
    String approval =
        reaction == Feedback.ALLOW_AUTO
            ? permissions.requestSecretaryPermission(project, before.taskId(), before.rule())
            : null;
    return database.transaction(
        db -> {
          var old = find(db, project, id);
          if (old == null)
            throw new IllegalArgumentException("Suggestion not found in this project");
          if (old.revision() != revision) throw new SecretaryConflictException();
          var now = clock.instant();
          var next =
              switch (reaction) {
                case LATER ->
                    now.plusSeconds(delaySeconds == 0 ? 3600 : Math.max(60, delaySeconds));
                case DISMISS ->
                    now.plusSeconds(Math.max(7 * 86400L, settings(project).repeatSeconds()));
                default -> Instant.ofEpochMilli(Long.MAX_VALUE);
              };
          String status =
              switch (reaction) {
                case EXECUTE -> "EXECUTION_REQUESTED";
                case LATER -> "DEFERRED";
                case DISMISS -> "DISMISSED";
                case NEVER -> "MUTED";
                case ALLOW_AUTO -> "APPROVAL_REQUESTED";
              };
          var changed =
              new Suggestion(
                  old.id(),
                  project,
                  old.taskId(),
                  old.rule(),
                  status,
                  old.priority(),
                  revision + 1,
                  old.taskRevision(),
                  old.created(),
                  now,
                  next,
                  old.reason(),
                  approval);
          save(db, changed);
          history(db, changed, reaction.name(), approval == null ? status : approval);
          return changed;
        });
  }

  public Suggestion suggestion(String project, String id) {
    required(project);
    required(id);
    refresh(project);
    var value = database.read(db -> find(db, project, id));
    if (value == null) throw new IllegalArgumentException("Suggestion not found in this project");
    return value;
  }

  /** Record an owned execution result without overwriting a later human dismissal or mute. */
  public void executionResult(
      String project, String id, long taskRevision, String execution, String state) {
    required(project);
    required(id);
    required(execution);
    database.transaction(
        db -> {
          var old = find(db, project, id);
          if (old == null || old.taskRevision() != taskRevision) return null;
          var now = clock.instant();
          if (Set.of("PROPOSED", "EXECUTION_REQUESTED", "APPROVAL_REQUESTED")
              .contains(old.status())) {
            boolean stable = Set.of("COMPLETED", "PREPARED", "PAUSED", "UNCERTAIN").contains(state);
            var changed =
                new Suggestion(
                    old.id(),
                    project,
                    old.taskId(),
                    old.rule(),
                    "EXECUTION_" + state,
                    old.priority(),
                    old.revision() + 1,
                    old.taskRevision(),
                    old.created(),
                    now,
                    stable
                        ? Instant.ofEpochMilli(Long.MAX_VALUE)
                        : now.plusSeconds(settings(project).repeatSeconds()),
                    old.reason(),
                    old.approvalId());
            save(db, changed);
            history(db, changed, "EXECUTION_RESULT", execution + ":" + state);
          } else history(db, old, "EXECUTION_RESULT", execution + ":" + state);
          return null;
        });
  }

  public List<Suggestion> suggestions(String project, int limit) {
    required(project);
    limit(limit);
    return database.read(
        db -> {
          var values = new ArrayList<Suggestion>();
          try (var q =
              db.prepareStatement(
                  "SELECT record FROM secretary_suggestions WHERE project=? ORDER BY priority,updated DESC,id LIMIT ?")) {
            q.setString(1, project);
            q.setInt(2, limit);
            try (var r = q.executeQuery()) {
              while (r.next())
                values.add(SecretaryJson.JSON.readValue(r.getString(1), Suggestion.class));
            }
          }
          return List.copyOf(values);
        });
  }

  public List<Suggestion> executionCandidates(String project, int offset, int limit) {
    required(project);
    refresh(project);
    limit(limit);
    if (offset < 0) throw new IllegalArgumentException("Invalid cursor");
    return database.read(
        db -> {
          var values = new ArrayList<Suggestion>();
          try (var q =
              db.prepareStatement(
                  "SELECT record FROM secretary_suggestions WHERE project=? AND status IN ('PROPOSED','EXECUTION_REQUESTED','APPROVAL_REQUESTED') ORDER BY id LIMIT ? OFFSET ?")) {
            q.setString(1, project);
            q.setInt(2, limit);
            q.setInt(3, offset);
            try (var r = q.executeQuery()) {
              while (r.next())
                values.add(SecretaryJson.JSON.readValue(r.getString(1), Suggestion.class));
            }
          }
          return List.copyOf(values);
        });
  }

  public List<History> history(String project, String id, int limit) {
    suggestion(project, id);
    limit(limit);
    return database.read(
        db -> {
          var values = new ArrayList<History>();
          try (var q =
              db.prepareStatement(
                  "SELECT sequence,action,revision,at,detail FROM secretary_suggestion_history WHERE project=? AND suggestion=? ORDER BY sequence DESC LIMIT ?")) {
            q.setString(1, project);
            q.setString(2, id);
            q.setInt(3, limit);
            try (var r = q.executeQuery()) {
              while (r.next())
                values.add(
                    new History(
                        r.getLong(1),
                        r.getString(2),
                        r.getLong(3),
                        Instant.ofEpochMilli(r.getLong(4)),
                        r.getString(5)));
            }
          }
          return List.copyOf(values);
        });
  }

  public Status status(String project) {
    long held =
        database.read(
            db -> {
              try (var q =
                  db.prepareStatement(
                      "SELECT COUNT(*) FROM secretary_notices WHERE project=? AND status='HELD'")) {
                q.setString(1, project);
                try (var r = q.executeQuery()) {
                  r.next();
                  return r.getLong(1);
                }
              }
            });
    return new Status(
        settings(project), enabled, activity(project), suggestions(project, 20), held);
  }

  private SecretaryActivity.Snapshot activity(String project) {
    var now = clock.instant();
    var selected = new SecretaryActivity.Snapshot("UNKNOWN", null, false, false);
    for (var source : activities) {
      try {
        var candidate = source.snapshot(project, now);
        if (candidate == null
            || candidate.observedAt() == null
            || candidate.observedAt().isAfter(now)
            || candidate.observedAt().isBefore(now.minusSeconds(120))) continue;
        if (candidate.busy()) return candidate;
        if (candidate.focused() || !selected.focused()) selected = candidate;
      } catch (RuntimeException unavailable) {
        /* Missing observation is unknown, not proof of idleness. */
      }
    }
    return selected;
  }

  private Admission assess(SecretaryNotifications.Notice notice) {
    if (life != null && !life.notificationAllowed(notice.category().name(), notice.id()))
      return new Admission(Decision.HOLD, "life_policy");
    if (notice.kind().equals("SUGGESTION")) refresh(notice.projectId());
    if (notice.kind().equals("SUGGESTION") && !liveSuggestion(notice))
      return new Admission(Decision.SUPPRESS, "superseded_feedback");
    if (!database.read(db -> hasSettings(db, notice.projectId())))
      return new Admission(Decision.ALLOW, "unconfigured");
    var settings = settings(notice.projectId());
    if (settings.stopped()) return new Admission(Decision.SUPPRESS, "stopped");
    if (settings.snoozedUntil() != null && settings.snoozedUntil().isAfter(clock.instant()))
      return new Admission(Decision.HOLD, "snoozed");
    var context = activity(notice.projectId());
    if (notice.priority() > 1 && (context.busy() || context.focused()))
      return new Admission(Decision.HOLD, "focused_or_busy");
    return new Admission(Decision.ALLOW, "eligible");
  }

  private boolean liveSuggestion(SecretaryNotifications.Notice notice) {
    return database.read(
        db -> {
          try (var q =
              db.prepareStatement(
                  "SELECT record FROM secretary_suggestions WHERE project=? AND task=? AND status='PROPOSED'")) {
            q.setString(1, notice.projectId());
            q.setString(2, notice.taskId());
            try (var r = q.executeQuery()) {
              while (r.next()) {
                var value = SecretaryJson.JSON.readValue(r.getString(1), Suggestion.class);
                if (uuid(value.id() + ":" + value.revision()).equals(notice.id())) return true;
              }
              return false;
            }
          }
        });
  }

  @Override
  public Admission admit(SecretaryNotifications.Notice notice) {
    var decision = assess(notice);
    if (decision.decision() != Decision.ALLOW || decision.reason().equals("unconfigured"))
      return decision;
    return database.transaction(
        db -> {
          long previous = state(db, "notice:last:" + notice.projectId());
          if (notice.priority() > 1
              && previous != 0
              && previous > clock.millis() - settings(notice.projectId()).minimumSeconds() * 1000)
            return new Admission(Decision.HOLD, "frequency");
          state(db, "notice:last:" + notice.projectId(), clock.millis());
          return decision;
        });
  }

  @Override
  public int release(String project) {
    if (project == null) {
      var projects =
          database.read(
              db -> {
                var values = new ArrayList<String>();
                try (var q = db.createStatement();
                    var r =
                        q.executeQuery(
                            "SELECT DISTINCT project FROM secretary_notices WHERE status='HELD' LIMIT 16")) {
                  while (r.next()) values.add(r.getString(1));
                }
                return values;
              });
      int total = 0;
      for (var owner : projects) total += release(owner);
      return total;
    }
    required(project);
    var routing = followups.getObject().settings(project);
    var held =
        database.read(
            db -> {
              var values = new ArrayList<SecretaryNotifications.Notice>();
              try (var q =
                  db.prepareStatement(
                      "SELECT record FROM secretary_notices WHERE project=? AND status='HELD' ORDER BY priority,CASE WHEN kind='DIGEST' THEN 0 ELSE 1 END,created,id LIMIT 8")) {
                q.setString(1, project);
                try (var r = q.executeQuery()) {
                  while (r.next())
                    values.add(
                        SecretaryJson.JSON.readValue(
                            r.getString(1), SecretaryNotifications.Notice.class));
                }
              }
              return values;
            });
    var eligible = new ArrayList<SecretaryNotifications.Notice>();
    for (var notice : held) {
      boolean valid =
          routing.enabled()
              && routing.notifications()
              && routing.destination().equals(notice.destination());
      if (notice.taskId() != null && !notice.kind().equals("COMPLETED")) {
        var task = ledger.get(project, notice.taskId());
        valid &=
            task.status() == SecretaryTask.Status.OPEN
                || task.status() == SecretaryTask.Status.WAITING;
      }
      var admission = assess(notice);
      if (!valid || admission.decision() == Decision.SUPPRESS) suppress(notice.id());
      else if (admission.decision() == Decision.ALLOW) eligible.add(notice);
    }
    if (eligible.isEmpty()) return 0;
    long previous = database.read(db -> state(db, "notice:last:" + project));
    if (previous != 0 && previous > clock.millis() - settings(project).minimumSeconds() * 1000)
      eligible.removeIf(n -> n.priority() > 1);
    if (eligible.isEmpty()) return 0;
    return database.transaction(
        db -> {
          var existingDigest =
              eligible.stream()
                  .filter(
                      n ->
                          n.kind().equals("DIGEST")
                              && n.priority() <= eligible.getFirst().priority())
                  .findFirst();
          if (existingDigest.isPresent()) {
            try (var q =
                db.prepareStatement(
                    "UPDATE secretary_notices SET status='PENDING',next=?,error='' WHERE project=? AND id=? AND status='HELD'")) {
              q.setLong(1, clock.millis());
              q.setString(2, project);
              q.setString(3, existingDigest.get().id());
              return q.executeUpdate();
            }
          }
          var claimed = new ArrayList<SecretaryNotifications.Notice>();
          for (var notice : eligible) {
            if (notice.kind().equals("DIGEST")) continue;
            try (var q =
                db.prepareStatement(
                    "UPDATE secretary_notices SET status='BUNDLED' WHERE id=? AND status='HELD'")) {
              q.setString(1, notice.id());
              if (q.executeUpdate() == 1) claimed.add(notice);
            }
          }
          if (claimed.isEmpty()) return 0;
          String ids =
              claimed.stream()
                  .map(SecretaryNotifications.Notice::id)
                  .sorted()
                  .collect(java.util.stream.Collectors.joining(","));
          queue(
              db,
              new SecretaryNotifications.Notice(
                  uuid("digest:" + project + ":" + ids),
                  project,
                  null,
                  "DIGEST",
                  "保留した通知 " + claimed.size() + "件。/secretary notifications で内容を確認してください。",
                  claimed.stream()
                      .mapToInt(SecretaryNotifications.Notice::priority)
                      .min()
                      .orElse(3),
                  routing.destination(),
                  clock.instant()));
          return claimed.size();
        });
  }

  private void suppress(String id) {
    database.transaction(
        db -> {
          try (var q =
              db.prepareStatement(
                  "UPDATE secretary_notices SET status='SUPPRESSED' WHERE id=? AND status='HELD'")) {
            q.setString(1, id);
            q.executeUpdate();
          }
          return null;
        });
  }

  static void queue(Connection db, SecretaryNotifications.Notice notice)
      throws java.sql.SQLException, java.io.IOException {
    try (var q =
        db.prepareStatement(
            "INSERT OR IGNORE INTO secretary_notices(id,project,task,kind,status,attempts,next,record,priority,created) VALUES(?,?,?,?,'PENDING',0,?,?,?,?)")) {
      q.setString(1, notice.id());
      q.setString(2, notice.projectId());
      q.setString(3, notice.taskId());
      q.setString(4, notice.kind());
      q.setLong(5, notice.created().toEpochMilli());
      q.setString(6, SecretaryJson.JSON.writeValueAsString(notice));
      q.setInt(7, notice.priority());
      q.setLong(8, notice.created().toEpochMilli());
      q.executeUpdate();
    }
  }

  private static Suggestion find(Connection db, String project, String id)
      throws java.sql.SQLException, java.io.IOException {
    try (var q =
        db.prepareStatement("SELECT record FROM secretary_suggestions WHERE project=? AND id=?")) {
      q.setString(1, project);
      q.setString(2, id);
      try (var r = q.executeQuery()) {
        return r.next() ? SecretaryJson.JSON.readValue(r.getString(1), Suggestion.class) : null;
      }
    }
  }

  private static void save(Connection db, Suggestion value)
      throws java.sql.SQLException, java.io.IOException {
    try (var q =
        db.prepareStatement(
            "INSERT INTO secretary_suggestions VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET priority=excluded.priority,status=excluded.status,next=excluded.next,updated=excluded.updated,record=excluded.record")) {
      q.setString(1, value.id());
      q.setString(2, value.projectId());
      q.setString(3, value.taskId());
      q.setString(4, value.rule());
      q.setInt(5, value.priority());
      q.setString(6, value.status());
      q.setLong(7, value.notBefore().toEpochMilli());
      q.setLong(8, value.updated().toEpochMilli());
      q.setString(9, SecretaryJson.JSON.writeValueAsString(value));
      q.executeUpdate();
    }
  }

  private static void history(Connection db, Suggestion value, String action, String detail)
      throws java.sql.SQLException {
    try (var q =
        db.prepareStatement(
            "INSERT INTO secretary_suggestion_history(project,suggestion,action,revision,at,detail) VALUES(?,?,?,?,?,?)")) {
      q.setString(1, value.projectId());
      q.setString(2, value.id());
      q.setString(3, action);
      q.setLong(4, value.revision());
      q.setLong(5, value.updated().toEpochMilli());
      q.setString(6, detail);
      q.executeUpdate();
    }
  }

  private static long state(Connection db, String key) throws java.sql.SQLException {
    try (var q = db.prepareStatement("SELECT value FROM secretary_followup_state WHERE key=?")) {
      q.setString(1, key);
      try (var r = q.executeQuery()) {
        return r.next() ? r.getLong(1) : 0;
      }
    }
  }

  private static void state(Connection db, String key, long value) throws java.sql.SQLException {
    try (var q =
        db.prepareStatement(
            "INSERT INTO secretary_followup_state VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value WHERE value<>excluded.value")) {
      q.setString(1, key);
      q.setLong(2, value);
      q.executeUpdate();
    }
  }

  private static String uuid(String input) {
    return UUID.nameUUIDFromBytes(input.getBytes(java.nio.charset.StandardCharsets.UTF_8))
        .toString();
  }

  private static void required(String value) {
    if (value == null
        || value.isBlank()
        || value.length() > 128
        || value.chars().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException("Invalid project or identifier");
  }

  private static void limit(int value) {
    if (value < 1 || value > 1000) throw new IllegalArgumentException("Invalid limit");
  }

  @Override
  public void tick() {
    if (!enabled || !busy.compareAndSet(false, true)) return;
    try {
      worker.execute(
          () -> {
            try {
              poll();
            } finally {
              busy.set(false);
            }
          });
    } catch (RejectedExecutionException closed) {
      busy.set(false);
    }
  }

  private void poll() {
    try {
      var projects =
          database.read(
              db -> {
                var values = new ArrayList<String>();
                try (var q = db.createStatement();
                    var r =
                        q.executeQuery(
                            "SELECT project FROM secretary_orchestration ORDER BY project LIMIT 256")) {
                  while (r.next()) values.add(r.getString(1));
                }
                return values;
              });
      int cursor = Math.toIntExact(database.read(db -> state(db, "proposal:projects")));
      for (int i = 0; i < Math.min(16, projects.size()); i++) {
        if (Thread.currentThread().isInterrupted()) return;
        var project = projects.get(Math.floorMod(cursor + i, projects.size()));
        try {
          evaluate(project);
          release(project);
        } catch (RuntimeException unavailable) {
          org.slf4j.LoggerFactory.getLogger(getClass())
              .warn(
                  "Secretary orchestration unavailable: {}",
                  unavailable.getClass().getSimpleName());
        }
      }
      database.transaction(
          db -> {
            state(
                db,
                "proposal:projects",
                projects.isEmpty() ? 0 : Math.floorMod(cursor + 16, projects.size()));
            return null;
          });
    } catch (RuntimeException unavailable) {
      org.slf4j.LoggerFactory.getLogger(getClass())
          .warn("Secretary orchestration unavailable: {}", unavailable.getClass().getSimpleName());
    }
  }

  @Override
  @jakarta.annotation.PreDestroy
  public void close() {
    worker.shutdownNow();
    try {
      worker.awaitTermination(5, TimeUnit.SECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }
}
