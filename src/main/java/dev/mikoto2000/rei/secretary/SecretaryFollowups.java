package dev.mikoto2000.rei.secretary;

import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Deterministic reports, bounded monitoring and a durable idempotent notification outbox. */
@Component
@ConditionalOnProperty(name = "rei.secretary.enabled", havingValue = "true")
public final class SecretaryFollowups implements SecretaryTick, AutoCloseable {
  public record Settings(
      String projectId,
      boolean enabled,
      boolean notifications,
      String briefingTime,
      String reviewTime,
      String zone,
      long repeatSeconds,
      int notificationPriority,
      String destination) {}

  public record Delivery(
      SecretaryNotifications.Notice notice,
      String status,
      int attempts,
      Instant nextAttempt,
      String error) {}

  public record Dependency(String id, String state, long revision) {}

  public record Status(
      Settings settings,
      boolean backgroundEnabled,
      List<Delivery> notifications,
      List<Dependency> dependencies) {
    public Status {
      notifications = List.copyOf(notifications);
      dependencies = List.copyOf(dependencies);
    }
  }

  public record Report(
      LocalDate date,
      String zone,
      boolean review,
      Map<String, List<SecretaryTask>> sections,
      List<SecretaryContext.Item> context,
      boolean partial) {
    public Report {
      var copy = new LinkedHashMap<String, List<SecretaryTask>>();
      sections.forEach((key, value) -> copy.put(key, List.copyOf(value)));
      sections = java.util.Collections.unmodifiableMap(copy);
      context = List.copyOf(context);
    }
  }

  private final SecretaryDatabase database;
  private final SecretaryLedger ledger;
  private final Clock clock;
  private final SecretaryNotifications notifications;
  private final boolean enabled;
  private List<SecretaryContext> contexts = List.of();
  private SecretaryDependencies dependencies;

  @Autowired
  public void setDependencies(ObjectProvider<SecretaryDependencies> dependencies) {
    this.dependencies = dependencies.getIfAvailable();
  }

  private final AtomicBoolean busy = new AtomicBoolean();
  private final ThreadPoolExecutor worker =
      new ThreadPoolExecutor(
          1,
          1,
          0,
          TimeUnit.SECONDS,
          new ArrayBlockingQueue<>(1),
          r -> {
            var t = new Thread(r, "secretary-followups");
            t.setDaemon(true);
            return t;
          });

  @Autowired
  public SecretaryFollowups(
      SecretaryDatabase database,
      SecretaryLedger ledger,
      Clock clock,
      ObjectProvider<SecretaryNotifications> notifications,
      @Value("${rei.secretary.followups-enabled:false}") boolean enabled) {
    this(
        database,
        ledger,
        clock,
        notifications.getIfAvailable(
            () ->
                n -> {
                  throw new IllegalStateException("Notification ingress unavailable");
                }),
        enabled);
  }

  public SecretaryFollowups(
      SecretaryDatabase database,
      SecretaryLedger ledger,
      Clock clock,
      SecretaryNotifications notifications,
      boolean enabled) {
    this.database = database;
    this.ledger = ledger;
    this.clock = clock;
    this.notifications = notifications;
    this.enabled = enabled;
  }

  @Autowired
  public void setContexts(ObjectProvider<SecretaryContext> contexts) {
    this.contexts = contexts.orderedStream().limit(16).toList();
  }

  public Settings settings(String project) {
    required(project);
    return database.read(
        db -> {
          try (var query =
              db.prepareStatement("SELECT record FROM secretary_settings WHERE project=?")) {
            query.setString(1, project);
            try (var rows = query.executeQuery()) {
              return rows.next()
                  ? SecretaryJson.JSON.readValue(rows.getString(1), Settings.class)
                  : new Settings(
                      project, false, false, "", "", clock.getZone().getId(), 86400, 3, "INBOX");
            }
          }
        });
  }

  public Settings configure(Settings settings) {
    if (settings == null) throw new IllegalArgumentException("Settings required");
    required(settings.projectId());
    if (settings.briefingTime() == null || settings.reviewTime() == null)
      throw new IllegalArgumentException("Use empty time to disable a report");
    try {
      if (settings.zone() == null) throw new IllegalArgumentException("Zone required");
      ZoneId.of(settings.zone());
      if (!settings.briefingTime().isEmpty()) LocalTime.parse(settings.briefingTime());
      if (!settings.reviewTime().isEmpty()) LocalTime.parse(settings.reviewTime());
    } catch (java.time.DateTimeException invalid) {
      throw new IllegalArgumentException("Invalid zone or report time");
    }
    if (settings.repeatSeconds() < 60
        || settings.repeatSeconds() > 2592000
        || settings.notificationPriority() < 1
        || settings.notificationPriority() > 5
        || !Set.of("INBOX", "DELIVERY").contains(settings.destination()))
      throw new IllegalArgumentException("Invalid notification settings");
    return database.transaction(
        db -> {
          try (var query = db.createStatement();
              var rows = query.executeQuery("SELECT COUNT(*) FROM secretary_settings")) {
            rows.next();
            if (rows.getInt(1) >= 256 && !configured(db, settings.projectId()))
              throw new IllegalStateException("Secretary project capacity reached");
          }
          try (var write =
              db.prepareStatement(
                  "INSERT INTO secretary_settings VALUES(?,?,?) ON CONFLICT(project) DO UPDATE SET record=excluded.record,configured=excluded.configured")) {
            write.setString(1, settings.projectId());
            write.setString(2, SecretaryJson.JSON.writeValueAsString(settings));
            write.setLong(3, clock.millis());
            write.executeUpdate();
          }
          return settings;
        });
  }

  private static boolean configured(Connection db, String project) throws java.sql.SQLException {
    try (var query = db.prepareStatement("SELECT 1 FROM secretary_settings WHERE project=?")) {
      query.setString(1, project);
      try (var rows = query.executeQuery()) {
        return rows.next();
      }
    }
  }

  public Report report(String project, LocalDate requested, boolean review) {
    var settings = settings(project);
    var zone = ZoneId.of(settings.zone());
    var date = requested == null ? clock.instant().atZone(zone).toLocalDate() : requested;
    var tasks = ledger.list(project, 0, 1000);
    var sections = new LinkedHashMap<String, List<SecretaryTask>>();
    var open = tasks.stream().filter(SecretaryFollowups::active).toList();
    sections.put("today", open.stream().filter(t -> date.equals(t.due())).toList());
    sections.put(
        "near",
        open.stream()
            .filter(
                t ->
                    t.due() != null
                        && !t.due().isBefore(date)
                        && !t.due().isAfter(date.plusDays(3)))
            .toList());
    sections.put("important", open.stream().filter(t -> t.priority() <= 2).toList());
    sections.put(
        "waiting", open.stream().filter(t -> t.status() == SecretaryTask.Status.WAITING).toList());
    sections.put(
        "yesterday",
        tasks.stream()
            .filter(
                t ->
                    t.status() == SecretaryTask.Status.DONE
                        && t.updated().atZone(zone).toLocalDate().equals(date.minusDays(1)))
            .toList());
    sections.put("continuation", open);
    sections.put(
        "next",
        open.stream().filter(t -> t.status() == SecretaryTask.Status.OPEN).limit(10).toList());
    sections.put(
        "completed",
        tasks.stream()
            .filter(
                t ->
                    t.status() == SecretaryTask.Status.DONE
                        && t.updated().atZone(zone).toLocalDate().equals(date))
            .toList());
    sections.put("unfinished", open);
    sections.put(
        "new",
        tasks.stream().filter(t -> t.created().atZone(zone).toLocalDate().equals(date)).toList());
    sections.put(
        "overdue", open.stream().filter(t -> t.due() != null && t.due().isBefore(date)).toList());
    sections.put("carryover", open);
    sections.put(
        "neglected",
        open.stream()
            .filter(t -> t.updated().isBefore(date.minusDays(14).atStartOfDay(zone).toInstant()))
            .toList());
    var information = new ArrayList<SecretaryContext.Item>();
    boolean partial = tasks.size() == 1000;
    for (var provider : contexts) {
      try {
        var items = provider.context(project, date, zone);
        if (items.size() > 256) partial = true;
        information.addAll(items.stream().limit(256).toList());
      } catch (RuntimeException unavailable) {
        partial = true;
      }
    }
    return new Report(date, zone.getId(), review, sections, information, partial);
  }

  /** A duplicate/older observation cannot roll a dependency backwards. */
  public void observeDependency(String project, String id, String state, long revision) {
    required(project);
    required(id);
    if (revision < 0
        || !Set.of("RUNNING", "WAITING", "BLOCKED", "COMPLETED", "FAILED", "CANCELLED")
            .contains(state)) throw new IllegalArgumentException("Invalid dependency fact");
    database.transaction(
        db -> {
          try (var write =
              db.prepareStatement(
                  "INSERT INTO secretary_dependencies VALUES(?,?,?,?) ON CONFLICT(project,id) DO UPDATE SET state=excluded.state,revision=excluded.revision WHERE excluded.revision>secretary_dependencies.revision")) {
            write.setString(1, project);
            write.setString(2, id);
            write.setString(3, state);
            write.setLong(4, revision);
            write.executeUpdate();
          }
          return null;
        });
  }

  @Override
  public void tick() {
    if (!enabled || worker.isShutdown() || !busy.compareAndSet(false, true)) return;
    try {
      worker.execute(
          () -> {
            try {
              poll();
            } catch (RuntimeException failure) {
              org.slf4j.LoggerFactory.getLogger(getClass())
                  .warn("Secretary followup failed: {}", failure.getClass().getSimpleName());
            } finally {
              busy.set(false);
            }
          });
    } catch (java.util.concurrent.RejectedExecutionException closed) {
      busy.set(false);
    }
  }

  public synchronized void poll() {
    var projects =
        database.read(
            db -> {
              try (var query = db.createStatement();
                  var rows =
                      query.executeQuery(
                          "SELECT project FROM secretary_settings ORDER BY project LIMIT 256")) {
                var result = new ArrayList<String>();
                while (rows.next()) result.add(rows.getString(1));
                return result;
              }
            });
    if (projects.isEmpty()) return;
    int offset = state("projects");
    if (offset >= projects.size()) offset = 0;
    for (int i = offset; i < Math.min(offset + 16, projects.size()); i++) {
      if (Thread.currentThread().isInterrupted()) return;
      check(projects.get(i));
    }
    state("projects", offset + 16 >= projects.size() ? 0 : offset + 16);
    deliver(null);
  }

  public synchronized List<Delivery> checkNow(String project) {
    required(project);
    check(project);
    deliver(project);
    return history(project, 100);
  }

  public boolean backgroundEnabled() {
    return enabled;
  }

  public Status status(String project) {
    required(project);
    var facts =
        database.read(
            db -> {
              try (var query =
                  db.prepareStatement(
                      "SELECT id,state,revision FROM secretary_dependencies WHERE project=? ORDER BY id LIMIT 100")) {
                query.setString(1, project);
                var result = new ArrayList<Dependency>();
                try (var rows = query.executeQuery()) {
                  while (rows.next())
                    result.add(
                        new Dependency(rows.getString(1), rows.getString(2), rows.getLong(3)));
                }
                return List.copyOf(result);
              }
            });
    return new Status(settings(project), enabled, history(project, 20), facts);
  }

  public void check(String project) {
    var settings = settings(project);
    if (!settings.enabled()) return;
    var zone = ZoneId.of(settings.zone());
    var local = clock.instant().atZone(zone);
    int offset = state("scan:" + project);
    var tasks = ledger.list(project, offset, 64);
    state("scan:" + project, tasks.size() < 64 ? 0 : offset + 64);
    for (var task : tasks) {
      if (Thread.currentThread().isInterrupted()) return;
      var current = reconcile(task);
      if (!settings.notifications()
          || current.status() == SecretaryTask.Status.CANDIDATE
          || current.status() == SecretaryTask.Status.CANCELLED) continue;
      if (current.status() == SecretaryTask.Status.DONE) {
        long configured =
            database.read(
                db -> {
                  try (var q =
                      db.prepareStatement(
                          "SELECT configured FROM secretary_settings WHERE project=?")) {
                    q.setString(1, project);
                    try (var r = q.executeQuery()) {
                      return r.next() ? r.getLong(1) : Long.MAX_VALUE;
                    }
                  }
                });
        if (current.updated().toEpochMilli() >= configured)
          enqueue(
              settings,
              current,
              "COMPLETED",
              current.id() + ":done:" + current.revision(),
              "完了: " + current.title());
        continue;
      }
      String kind =
          current.due() != null && current.due().isBefore(local.toLocalDate())
              ? "OVERDUE"
              : current.due() != null && !current.due().isAfter(local.toLocalDate().plusDays(1))
                  ? "DUE"
                  : current.status() == SecretaryTask.Status.WAITING ? "WAITING" : "RECHECK";
      if (kind.equals("RECHECK") && current.updated().isAfter(clock.instant().minusSeconds(86400)))
        continue;
      String key =
          current.id()
              + ":"
              + kind
              + ":"
              + current.due()
              + ":"
              + (clock.instant().getEpochSecond() / settings.repeatSeconds());
      enqueue(settings, current, kind, key, kind + ": " + current.title());
    }
    if (settings.notifications()) {
      scheduleReport(settings, local.toLocalDate(), local.toLocalTime(), false);
      scheduleReport(settings, local.toLocalDate(), local.toLocalTime(), true);
    }
  }

  private SecretaryTask reconcile(SecretaryTask task) {
    if (!active(task)) return task;
    var completion =
        task.references().stream()
            .filter(r -> r.startsWith("completion:dependency:"))
            .map(r -> r.substring(22))
            .toList();
    var waiting =
        task.references().stream()
            .filter(r -> r.startsWith("dependency:"))
            .map(r -> r.substring(11))
            .toList();
    try {
      if (!completion.isEmpty() && satisfied(task.projectId(), completion))
        return ledger.transition(
            task.projectId(), task.id(), task.revision(), SecretaryTask.Status.DONE);
      if (task.status() == SecretaryTask.Status.WAITING
          && !waiting.isEmpty()
          && satisfied(task.projectId(), waiting))
        return ledger.transition(
            task.projectId(), task.id(), task.revision(), SecretaryTask.Status.OPEN);
    } catch (SecretaryConflictException conflict) {
      return ledger.get(task.projectId(), task.id());
    }
    return task;
  }

  private boolean satisfied(String project, List<String> ids) {
    if (dependencies != null) {
      for (var id : ids) {
        try {
          var fact = dependencies.fact(project, id);
          if (fact.isEmpty()) return false;
          observeDependency(project, id, fact.get().state(), fact.get().revision());
          if (!fact.get().state().equals("COMPLETED")) return false;
        } catch (RuntimeException unavailable) {
          return false;
        }
      }
      return true;
    }
    return database.read(
        db -> {
          try (var query =
              db.prepareStatement(
                  "SELECT state FROM secretary_dependencies WHERE project=? AND id=?")) {
            for (var id : ids) {
              query.setString(1, project);
              query.setString(2, id);
              try (var rows = query.executeQuery()) {
                if (!rows.next() || !rows.getString(1).equals("COMPLETED")) return false;
              }
            }
            return true;
          }
        });
  }

  private void scheduleReport(Settings settings, LocalDate date, LocalTime time, boolean review) {
    String configured = review ? settings.reviewTime() : settings.briefingTime();
    if (configured.isEmpty() || time.isBefore(LocalTime.parse(configured))) return;
    String kind = review ? "REVIEW" : "BRIEFING";
    // One persisted notice per local date, including repeated clock hours and restarts.
    var summary = report(settings.projectId(), date, review);
    String message =
        kind
            + " "
            + date
            + ": 今日="
            + summary.sections().get("today").size()
            + " 期限超過="
            + summary.sections().get("overdue").size()
            + " 待機="
            + summary.sections().get("waiting").size()
            + " 完了="
            + summary.sections().get("completed").size()
            + "; /secretary "
            + (review ? "review" : "briefing");
    enqueue(settings, null, kind, kind + ":" + date, message);
  }

  private void enqueue(
      Settings settings, SecretaryTask task, String kind, String key, String message) {
    var notice =
        new SecretaryNotifications.Notice(
            UUID.nameUUIDFromBytes(
                    (settings.projectId() + ":" + key)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .toString(),
            settings.projectId(),
            task == null ? null : task.id(),
            kind,
            message,
            settings.notificationPriority(),
            settings.destination(),
            clock.instant());
    boolean exists =
        database.read(
            db -> {
              try (var q = db.prepareStatement("SELECT 1 FROM secretary_notices WHERE id=?")) {
                q.setString(1, notice.id());
                try (var r = q.executeQuery()) {
                  return r.next();
                }
              }
            });
    if (exists) return;
    database.transaction(
        db -> {
          if (notice.taskId() != null && !notice.kind().equals("COMPLETED")) {
            try (var query =
                db.prepareStatement(
                    "SELECT 1 FROM secretary_notices WHERE project=? AND task=? AND kind=? AND created>? LIMIT 1")) {
              query.setString(1, notice.projectId());
              query.setString(2, notice.taskId());
              query.setString(3, notice.kind());
              query.setLong(4, notice.created().toEpochMilli() - settings.repeatSeconds() * 1000);
              try (var rows = query.executeQuery()) {
                if (rows.next()) return null;
              }
            }
          }
          try (var write =
              db.prepareStatement(
                  "INSERT OR IGNORE INTO secretary_notices(id,project,task,kind,status,attempts,next,record,priority,created) VALUES(?,?,?,?,'PENDING',0,?,?,?,?)")) {
            write.setString(1, notice.id());
            write.setString(2, notice.projectId());
            write.setString(3, notice.taskId());
            write.setString(4, kind);
            write.setLong(5, clock.millis());
            write.setString(6, SecretaryJson.JSON.writeValueAsString(notice));
            write.setInt(7, notice.priority());
            write.setLong(8, notice.created().toEpochMilli());
            write.executeUpdate();
          }
          return null;
        });
  }

  private void deliver(String project) {
    database.transaction(
        db -> {
          try (var write =
              project == null
                  ? db.prepareStatement(
                      "UPDATE secretary_notices SET status='FAILED',error='delivery_result_unconfirmed' WHERE status='SENDING' AND attempts>=5 AND next<=?")
                  : db.prepareStatement(
                      "UPDATE secretary_notices SET status='FAILED',error='delivery_result_unconfirmed' WHERE status='SENDING' AND attempts>=5 AND next<=? AND project=?")) {
            write.setLong(1, clock.millis());
            if (project != null) write.setString(2, project);
            write.executeUpdate();
          }
          return null;
        });
    for (int i = 0; i < 8; i++) {
      if (Thread.currentThread().isInterrupted()) return;
      var claimed =
          database.transaction(
              db -> {
                try (var query =
                    project == null
                        ? db.prepareStatement(
                            "SELECT id,record FROM secretary_notices WHERE status IN ('PENDING','SENDING') AND next<=? AND attempts<5 ORDER BY priority,next,id LIMIT 1")
                        : db.prepareStatement(
                            "SELECT id,record FROM secretary_notices WHERE status IN ('PENDING','SENDING') AND next<=? AND attempts<5 AND project=? ORDER BY priority,next,id LIMIT 1")) {
                  query.setLong(1, clock.millis());
                  if (project != null) query.setString(2, project);
                  try (var rows = query.executeQuery()) {
                    if (!rows.next()) return null;
                    String id = rows.getString(1);
                    var notice =
                        SecretaryJson.JSON.readValue(
                            rows.getString(2), SecretaryNotifications.Notice.class);
                    try (var write =
                        db.prepareStatement(
                            "UPDATE secretary_notices SET status='SENDING',attempts=attempts+1,next=? WHERE id=?")) {
                      write.setLong(1, clock.millis() + 60000);
                      write.setString(2, id);
                      write.executeUpdate();
                    }
                    return notice;
                  }
                }
              });
      if (claimed == null) return;
      var settings = settings(claimed.projectId());
      boolean eligible =
          settings.enabled()
              && settings.notifications()
              && settings.destination().equals(claimed.destination());
      if (claimed.taskId() != null && !claimed.kind().equals("COMPLETED"))
        eligible &= active(ledger.get(claimed.projectId(), claimed.taskId()));
      if (!eligible) {
        finish(claimed, "SUPPRESSED", "");
        continue;
      }
      try {
        notifications.deliver(claimed);
        finish(claimed, "DELIVERED", "");
      } catch (RuntimeException failure) {
        finish(claimed, "PENDING", failure.getClass().getSimpleName());
      }
    }
  }

  private void finish(SecretaryNotifications.Notice notice, String status, String error) {
    database.transaction(
        db -> {
          try (var write =
              db.prepareStatement(
                  "UPDATE secretary_notices SET status=CASE WHEN attempts>=5 AND ?='PENDING' THEN 'FAILED' ELSE ? END,error=?,next=?+min(3600000,60000*(1 << min(attempts-1,6))) WHERE id=? AND status='SENDING'")) {
            write.setString(1, status);
            write.setString(2, status);
            write.setString(3, error);
            write.setLong(4, clock.millis());
            write.setString(5, notice.id());
            write.executeUpdate();
          }
          return null;
        });
  }

  public List<Delivery> history(String project, int limit) {
    required(project);
    if (limit < 1 || limit > 1000) throw new IllegalArgumentException("Invalid history limit");
    return database.read(
        db -> {
          try (var query =
              db.prepareStatement(
                  "SELECT record,status,attempts,next,error FROM secretary_notices WHERE project=? ORDER BY created DESC,id LIMIT ?")) {
            query.setString(1, project);
            query.setInt(2, limit);
            var result = new ArrayList<Delivery>();
            try (var rows = query.executeQuery()) {
              while (rows.next())
                result.add(
                    new Delivery(
                        SecretaryJson.JSON.readValue(
                            rows.getString(1), SecretaryNotifications.Notice.class),
                        rows.getString(2),
                        rows.getInt(3),
                        Set.of("PENDING", "SENDING").contains(rows.getString(2))
                            ? Instant.ofEpochMilli(rows.getLong(4))
                            : null,
                        rows.getString(5)));
            }
            return List.copyOf(result);
          }
        });
  }

  private int state(String key) {
    return database.read(
        db -> {
          try (var q =
              db.prepareStatement("SELECT value FROM secretary_followup_state WHERE key=?")) {
            q.setString(1, key);
            try (var r = q.executeQuery()) {
              return r.next() ? r.getInt(1) : 0;
            }
          }
        });
  }

  private void state(String key, int value) {
    database.transaction(
        db -> {
          try (var q =
              db.prepareStatement(
                  "INSERT INTO secretary_followup_state VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value WHERE value<>excluded.value")) {
            q.setString(1, key);
            q.setInt(2, value);
            q.executeUpdate();
          }
          return null;
        });
  }

  private static boolean active(SecretaryTask task) {
    return task.status() == SecretaryTask.Status.OPEN
        || task.status() == SecretaryTask.Status.WAITING;
  }

  private static void required(String value) {
    if (value == null || value.isBlank() || value.length() > 128)
      throw new IllegalArgumentException("Bounded identifier required");
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
