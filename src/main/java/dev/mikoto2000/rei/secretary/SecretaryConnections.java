package dev.mikoto2000.rei.secretary;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
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

/** Owner-scoped evidence and durable write admissions. External text never grants authority. */
@Component
@ConditionalOnProperty(name = "rei.secretary.enabled", havingValue = "true")
public final class SecretaryConnections
    implements SecretaryTick, SecretaryContext, SecretaryPreparationContext, AutoCloseable {
  public record Config(
      String projectId,
      String sessionId,
      String name,
      SecretaryProvider.Service service,
      String resource,
      boolean enabled,
      int pollSeconds,
      boolean importCandidates,
      boolean allowModelContent,
      String zone) {
    @com.fasterxml.jackson.annotation.JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
      throw new IllegalArgumentException("Unknown connection configuration field");
    }
  }

  public record Connection(
      String id,
      long version,
      Config config,
      String state,
      String reason,
      String cursor,
      Instant next,
      int failures,
      Instant updated) {}

  public record Event(long sequence, String item, String revision, String kind, Instant at) {}

  public record Spec(
      String projectId,
      String connectionId,
      long connectionVersion,
      Config config,
      String receipt,
      String operation,
      Map<String, String> arguments,
      String fingerprint,
      String identity,
      String policyFingerprint,
      Instant expires) {
    public Spec {
      arguments = Map.copyOf(arguments);
    }
  }

  public record Action(
      String id,
      Spec spec,
      String state,
      long revision,
      String permit,
      String authorization,
      int attempts,
      Instant next,
      SecretaryProvider.Outcome result,
      String reason,
      Instant created,
      Instant updated) {}

  public record ActionHistory(long sequence, String state, String reason, Instant at) {}

  private static final Set<String> TERMINAL =
      Set.of("COMPLETED", "CANCELLED", "REJECTED", "BLOCKED", "UNCERTAIN", "EXPIRED", "LIMITED");
  private static final Map<String, Set<String>> WRITES =
      Map.of(
          "CALENDAR_CREATE",
          Set.of("title", "start", "end", "description", "location"),
          "CALENDAR_UPDATE",
          Set.of("id", "etag", "title", "start", "end", "description", "location"),
          "CALENDAR_CANCEL",
          Set.of("id", "etag"),
          "MAIL_DRAFT",
          Set.of("to", "cc", "subject", "body", "threadId", "inReplyTo", "references"),
          "MAIL_SEND",
          Set.of(
              "to", "cc", "subject", "body", "threadId", "inReplyTo", "references", "trackReply"),
          "SLACK_POST",
          Set.of("body", "thread"),
          "SLACK_REPORT",
          Set.of("taskId", "taskRevision", "thread"));
  private volatile dev.mikoto2000.rei.life.LifePolicy life;

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  void setLifeContext(dev.mikoto2000.rei.life.LifePolicy value) {
    life = value;
  }

  private final SecretaryDatabase database;
  private final SecretaryLedger ledger;
  private final Clock clock;
  private final boolean automatic;
  private ObjectProvider<SecretaryProvider> providers;
  private ObjectProvider<SecretaryExecutionPolicy> policies;
  private ObjectProvider<SecretaryPermissions> permissions;
  private ObjectProvider<SecretaryPreparationRuntime> owners;
  private ObjectProvider<SecretaryReports> reports;
  private final AtomicBoolean busy = new AtomicBoolean(), closed = new AtomicBoolean();
  private final ThreadPoolExecutor worker =
      new ThreadPoolExecutor(
          1,
          1,
          0,
          TimeUnit.SECONDS,
          new ArrayBlockingQueue<>(1),
          r -> {
            var t = new Thread(r, "secretary-connections");
            t.setDaemon(true);
            return t;
          });

  public SecretaryConnections(
      SecretaryDatabase database,
      SecretaryLedger ledger,
      Clock clock,
      @Value("${rei.secretary.connections-enabled:false}") boolean automatic) {
    this.database = database;
    this.ledger = ledger;
    this.clock = clock;
    this.automatic = automatic;
  }

  @Autowired
  public void setServices(
      ObjectProvider<SecretaryProvider> providers,
      ObjectProvider<SecretaryExecutionPolicy> policies,
      ObjectProvider<SecretaryPermissions> permissions,
      ObjectProvider<SecretaryPreparationRuntime> owners,
      ObjectProvider<SecretaryReports> reports) {
    this.providers = providers;
    this.policies = policies;
    this.permissions = permissions;
    this.owners = owners;
    this.reports = reports;
  }

  private ObjectProvider<SecretaryPrivateModel> privateModels;
  private ObjectProvider<SecretaryDataPolicy> dataPolicies;

  @Value("${rei.secretary.external-content-to-model:false}")
  private boolean modelContent;

  @Value("${rei.secretary.private-model-endpoints:}")
  private String privateModelEndpoints = "";

  @Autowired
  public void setPrivacy(
      ObjectProvider<SecretaryPrivateModel> models, ObjectProvider<SecretaryDataPolicy> policies) {
    privateModels = models;
    dataPolicies = policies;
  }

  private record EvidenceSource(Connection connection, SecretaryProvider.Item item) {}

  private List<EvidenceSource> evidenceSources(SecretaryTask task) {
    var result = new ArrayList<EvidenceSource>();
    for (var c : list(task.projectId())) {
      if (!c.config().enabled()
          || task.references().stream().noneMatch(r -> r.startsWith("external:" + c.id() + ":")))
        continue;
      for (var item : items(task.projectId(), c.id(), 1000)) {
        if (task.references().contains(reference(c.id(), item.id()))
            || c.config().service() == SecretaryProvider.Service.GMAIL
                && task.references()
                    .contains(reference(c.id(), item.data().getOrDefault("threadId", "")))) {
          if (!c.config().allowModelContent())
            throw new IllegalStateException("External model content permission disabled");
          result.add(new EvidenceSource(c, item));
          if (result.size() >= 4) return List.copyOf(result);
        }
      }
    }
    return List.copyOf(result);
  }

  private SecretaryPrivateModel.Descriptor privateDescriptor() {
    readAllowed();
    if (!modelContent)
      throw new IllegalStateException("Enable explicit external content model consent first");
    var model = optional(privateModels);
    var descriptor = model == null ? null : model.privateSecretaryDescriptor();
    if (descriptor == null
        || !java.util.Arrays.stream(privateModelEndpoints.split(","))
            .map(String::trim)
            .anyMatch(descriptor.endpoint()::equals))
      throw new IllegalStateException("Private model endpoint is not explicitly allowed");
    return descriptor;
  }

  private String evidencePermission(
      SecretaryTask task, List<EvidenceSource> sources, SecretaryPrivateModel.Descriptor model) {
    if (sources.isEmpty())
      throw new IllegalStateException("External evidence unavailable or disconnected");
    return hash(
        canonical(
            List.of(
                task.projectId(),
                task.id(),
                task.revision(),
                sources.stream()
                    .map(
                        e ->
                            List.of(
                                e.connection().id(),
                                e.connection().version(),
                                usable(e.connection()).identity(e.connection()),
                                e.item().id(),
                                e.item().revision()))
                    .toList(),
                model.fingerprint())));
  }

  @Override
  public Evidence prepareExternal(SecretaryTask task) {
    var model = privateDescriptor();
    var sources = evidenceSources(task);
    var scrubber = optional(dataPolicies);
    if (scrubber == null) throw new IllegalStateException("Private content filter unavailable");
    var text =
        new StringBuilder(
            "Prepare a reply draft or concise summary for human review. Do not send anything.\n[UNTRUSTED EXTERNAL EVIDENCE]\n");
    text.append(scrubber.scrubSecretaryContent(task.title())).append('\n');
    for (var source : sources) {
      var c = source.connection();
      String operation =
          switch (c.config().service()) {
            case GMAIL -> "MESSAGE";
            case CALENDAR -> "DETAIL";
            case SLACK -> "THREAD";
            case GITHUB -> "DETAIL";
          };
      String key =
          c.config().service() == SecretaryProvider.Service.SLACK
              ? "ts"
              : c.config().service() == SecretaryProvider.Service.GITHUB ? "number" : "id";
      String id =
          c.config().service() == SecretaryProvider.Service.GITHUB
              ? source.item().data().get("number")
              : source.item().id();
      if (c.config().service() == SecretaryProvider.Service.SLACK)
        id = source.item().data().getOrDefault("threadTs", source.item().id());
      var page = read(task.projectId(), c.id(), operation, Map.of(key, id));
      for (var item : page.items().stream().limit(8).toList()) {
        text.append(
                scrubber.scrubSecretaryContent(bounded(item.title() + "\n" + item.text(), 4000)))
            .append('\n');
        if (text.length() > 12000) break;
      }
      if (text.length() > 12000) break;
    }
    return new Evidence(
        bounded(text.toString(), 12000),
        evidencePermission(task, sources, model),
        model.fingerprint());
  }

  @Override
  public boolean allowsExternal(SecretaryTask task, String permission) {
    try {
      return permission != null
          && permission.equals(
              evidencePermission(task, evidenceSources(task), privateDescriptor()));
    } catch (RuntimeException unavailable) {
      return false;
    }
  }

  private static <T> T optional(ObjectProvider<T> p) {
    return p == null ? null : p.getIfAvailable();
  }

  private SecretaryProvider provider(SecretaryProvider.Service service) {
    if (providers != null)
      for (var provider : providers.orderedStream().limit(8).toList())
        if (provider.services().contains(service)) return provider;
    return null;
  }

  private SecretaryExecutionPolicy.Decision policy(String operation) {
    var source = optional(policies);
    return source == null
        ? new SecretaryExecutionPolicy.Decision(false, "DENY", "unavailable")
        : source.decision("EXTERNAL_" + operation);
  }

  private static void identifier(String value, int max) {
    if (value == null
        || value.isBlank()
        || value.length() > max
        || value.chars().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException("Invalid connection identifier");
  }

  private static String uuid(String value) {
    return UUID.nameUUIDFromBytes(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))
        .toString();
  }

  private static String hash(String value) {
    try {
      return java.util.HexFormat.of()
          .formatHex(
              java.security.MessageDigest.getInstance("SHA-256")
                  .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException error) {
      throw new IllegalStateException(error);
    }
  }

  private static String encode(Object value) throws java.io.IOException {
    return SecretaryJson.JSON.writeValueAsString(value);
  }

  private static String canonical(Object value) {
    try {
      return encode(value);
    } catch (java.io.IOException error) {
      throw new IllegalArgumentException("Invalid external operation");
    }
  }

  private static String safe(String value) {
    return value != null && value.matches("[A-Za-z0-9_:.-]{1,100}") ? value : "external_failure";
  }

  private static String bounded(String value, int max) {
    return value == null ? "" : value.substring(0, Math.min(value.length(), max));
  }

  public Connection configure(Config value, long expectedVersion) {
    if (value == null || value.service() == null)
      throw new IllegalArgumentException("Connection required");
    identifier(value.projectId(), 128);
    identifier(value.sessionId(), 256);
    identifier(value.name(), 64);
    identifier(value.resource(), 256);
    if (value.pollSeconds() < 60 || value.pollSeconds() > 86400)
      throw new IllegalArgumentException("Polling interval must be 60..86400 seconds");
    try {
      if (value.zone() == null) throw new IllegalArgumentException("Connection timezone required");
      ZoneId.of(value.zone());
    } catch (java.time.DateTimeException invalid) {
      throw new IllegalArgumentException("Invalid connection timezone");
    }
    if (value.enabled()) {
      var owner = optional(owners);
      if (owner == null) throw new IllegalStateException("Project/Session validation unavailable");
      owner.validate(value.projectId(), value.sessionId());
    }
    String id = uuid(value.projectId() + ":" + value.name());
    return database.transaction(
        db -> {
          var old = findConnection(db, value.projectId(), id);
          if (old == null ? expectedVersion != -1 : old.version() != expectedVersion)
            throw new SecretaryConflictException();
          if (old == null)
            try (var q = db.createStatement();
                var r = q.executeQuery("SELECT COUNT(*) FROM secretary_connections")) {
              r.next();
              if (r.getInt(1) >= 256)
                throw new IllegalStateException("Connection capacity reached");
            }
          if (old != null
              && (!old.config().service().equals(value.service())
                  || !old.config().resource().equals(value.resource())))
            throw new IllegalArgumentException("Create a new name when changing service/resource");
          var next =
              new Connection(
                  id,
                  old == null ? 0 : old.version() + 1,
                  value,
                  value.enabled() ? "IDLE" : "DISABLED",
                  "",
                  old == null ? "" : old.cursor(),
                  clock.instant(),
                  0,
                  clock.instant());
          saveConnection(db, next);
          return next;
        });
  }

  private Connection connection(String project, String id) {
    identifier(project, 128);
    identifier(id, 128);
    var value = database.read(db -> findConnection(db, project, id));
    if (value == null) throw new IllegalArgumentException("Connection not found in Project");
    return value;
  }

  public Connection status(String project, String id) {
    var c = connection(project, id);
    if (!c.config().enabled()) return c;
    var source = provider(c.config().service());
    var auth = source == null ? null : source.status(c.config().service());
    return auth == null
            || Set.of("DISABLED", "UNCONFIGURED", "REAUTH_REQUIRED").contains(auth.state())
        ? new Connection(
            c.id(),
            c.version(),
            c.config(),
            auth == null ? "UNCONFIGURED" : auth.state(),
            auth == null ? "provider_unavailable" : safe(auth.reason()),
            c.cursor(),
            c.next(),
            c.failures(),
            c.updated())
        : c;
  }

  public List<Connection> list(String project) {
    identifier(project, 128);
    return database.read(
        db -> {
          var values = new ArrayList<Connection>();
          try (var q =
              db.prepareStatement(
                  "SELECT record FROM secretary_connections WHERE project=? ORDER BY name LIMIT 256")) {
            q.setString(1, project);
            try (var r = q.executeQuery()) {
              while (r.next())
                values.add(SecretaryJson.JSON.readValue(r.getString(1), Connection.class));
            }
          }
          return List.copyOf(values);
        });
  }

  public SecretaryProvider.Auth authStatus(String project, String id) {
    var c = connection(project, id);
    var source = provider(c.config().service());
    return source == null
        ? new SecretaryProvider.Auth("UNCONFIGURED", "", List.of(), "provider_unavailable")
        : source.status(c.config().service());
  }

  public SecretaryProvider.Auth authorize(String project, String id, boolean force) {
    var c = connection(project, id);
    var source = provider(c.config().service());
    if (source == null || !c.config().enabled())
      throw new IllegalStateException("Provider disabled");
    return source.authorizeConnection(c.config().service(), force);
  }

  public Connection disconnect(String project, String id, long version, boolean revoke) {
    var c = connection(project, id);
    if (c.version() != version) throw new SecretaryConflictException();
    if (revoke)
      throw new IllegalArgumentException(
          "Revoke credentials in the provider account console; local disconnect retains shared credentials");
    var v = c.config();
    return configure(
        new Config(
            v.projectId(),
            v.sessionId(),
            v.name(),
            v.service(),
            v.resource(),
            false,
            v.pollSeconds(),
            v.importCandidates(),
            v.allowModelContent(),
            v.zone()),
        version);
  }

  private SecretaryProvider usable(Connection c) {
    if (!c.config().enabled()) throw new IllegalStateException("Connection disabled");
    var source = provider(c.config().service());
    if (source == null
        || Set.of("DISABLED", "UNCONFIGURED", "REAUTH_REQUIRED")
            .contains(source.status(c.config().service()).state()))
      throw new SecretaryProvider.Failure("AUTH_REQUIRED", 0, false);
    var owner = optional(owners);
    if (owner == null) throw new IllegalStateException("Project/Session validation unavailable");
    owner.validate(c.config().projectId(), c.config().sessionId());
    return source;
  }

  private void readAllowed() {
    var authority = policy("READ");
    if (!authority.enabled() || !authority.decision().equals("AUTO_APPROVE"))
      throw new IllegalStateException("Explicit NETWORK_READ policy is required");
  }

  public SecretaryProvider.Page read(
      String project, String id, String operation, Map<String, String> arguments) {
    var c = connection(project, id);
    readAllowed();
    identifier(operation, 64);
    return usable(c).read(c, operation, arguments(arguments));
  }

  public Connection pollNow(String project, String id) {
    var c = connection(project, id);
    if (!c.config().enabled() || c.next().isAfter(clock.instant())) return c;
    try {
      readAllowed();
      var page = usable(c).poll(c, c.cursor());
      if (page.items().size() > 100 || page.cursor() == null || page.cursor().length() > 16384)
        throw new SecretaryProvider.Failure("INVALID_RESPONSE", 0, false);
      for (var item : page.items()) ingest(c, item);
      pollReplies(c);
      return updateConnection(
          c,
          page.partial() ? "SYNCING" : "CURRENT",
          "",
          page.cursor(),
          clock.instant().plusSeconds(c.config().pollSeconds()),
          0);
    } catch (SecretaryProvider.Failure failure) {
      return updateConnection(
          c,
          failure.code().equals("AUTH_REQUIRED") ? "REAUTH_REQUIRED" : "RETRY_WAIT",
          safe(failure.code()),
          c.cursor(),
          clock
              .instant()
              .plusSeconds(
                  Math.max(
                      failure.retrySeconds(), Math.min(3600, 60L << Math.min(c.failures(), 5)))),
          c.failures() + 1);
    } catch (RuntimeException failure) {
      return updateConnection(
          c,
          "BLOCKED",
          "read_unavailable",
          c.cursor(),
          clock.instant().plusSeconds(c.config().pollSeconds()),
          c.failures() + 1);
    }
  }

  private void pollReplies(Connection c) {
    if (c.config().service() != SecretaryProvider.Service.GMAIL) return;
    var waits =
        database.read(
            db -> {
              var rows = new ArrayList<String[]>();
              try (var q =
                  db.prepareStatement(
                      "SELECT thread,task,sent FROM secretary_external_waits WHERE connection=? AND project=? ORDER BY checked,task LIMIT 3")) {
                q.setString(1, c.id());
                q.setString(2, c.config().projectId());
                try (var r = q.executeQuery()) {
                  while (r.next())
                    rows.add(new String[] {r.getString(1), r.getString(2), r.getString(3)});
                }
              }
              return rows;
            });
    for (var wait : waits) {
      try {
        database.transaction(
            db -> {
              try (var q =
                  db.prepareStatement(
                      "UPDATE secretary_external_waits SET checked=? WHERE connection=? AND task=?")) {
                q.setLong(1, clock.millis());
                q.setString(2, c.id());
                q.setString(3, wait[1]);
                q.executeUpdate();
              }
              return null;
            });
        var page = usable(c).read(c, "THREAD", Map.of("id", wait[0]));
        boolean replied =
            page.items().stream()
                .anyMatch(
                    item ->
                        "true".equals(item.data().get("inbound"))
                            && wait[0].equals(item.data().get("threadId"))
                            && item.occurred() != null
                            && item.occurred().toEpochMilli() > Long.parseLong(wait[2]));
        if (!replied) continue;
        for (var item : page.items()) ingest(c, item);
        database.transaction(
            db -> {
              var task = ledger.getWithin(db, c.config().projectId(), wait[1]);
              if (task.status() == SecretaryTask.Status.WAITING)
                ledger.updateWithin(
                    db,
                    task.projectId(),
                    task.id(),
                    task.revision(),
                    task.title(),
                    task.intent(),
                    SecretaryTask.Status.OPEN,
                    task.due(),
                    task.priority(),
                    task.tags(),
                    task.references());
              try (var q =
                  db.prepareStatement(
                      "DELETE FROM secretary_external_waits WHERE connection=? AND project=? AND thread=? AND task=?")) {
                q.setString(1, c.id());
                q.setString(2, c.config().projectId());
                q.setString(3, wait[0]);
                q.setString(4, wait[1]);
                q.executeUpdate();
              }
              return null;
            });
      } catch (RuntimeException unavailable) {
        /* Other threads and services remain independent. */
      }
    }
  }

  private Connection updateConnection(
      Connection expected, String state, String reason, String cursor, Instant next, int failures) {
    return database.transaction(
        db -> {
          var c = findConnection(db, expected.config().projectId(), expected.id());
          if (c.version() != expected.version() || !c.config().enabled()) return c;
          var changed =
              new Connection(
                  c.id(),
                  c.version(),
                  c.config(),
                  state,
                  reason,
                  cursor,
                  next,
                  failures,
                  clock.instant());
          saveConnection(db, changed);
          return changed;
        });
  }

  private String destination(String project) {
    var source = optional(reports);
    return source == null ? "INBOX" : source.settings(project).destination();
  }

  private static void limit(int value) {
    if (value < 1 || value > 1000) throw new IllegalArgumentException("Invalid page limit");
  }

  private static Map<String, String> arguments(Map<String, String> supplied) {
    if (supplied == null || supplied.size() > 32)
      throw new IllegalArgumentException("Invalid external arguments");
    int total = 0;
    for (var entry : supplied.entrySet()) {
      identifier(entry.getKey(), 64);
      if (entry.getValue() == null || entry.getValue().length() > 32768)
        throw new IllegalArgumentException("Invalid external argument");
      total += entry.getValue().length();
    }
    if (total > 65536) throw new IllegalArgumentException("External payload too large");
    return Map.copyOf(supplied);
  }

  private static String reference(String connection, String id) {
    return "external:" + connection + ":" + hash(id);
  }

  private void ingest(Connection c, SecretaryProvider.Item supplied) {
    identifier(supplied.id(), 512);
    identifier(supplied.revision(), 256);
    identifier(supplied.kind(), 64);
    identifier(supplied.status(), 64);
    var item =
        new SecretaryProvider.Item(
            supplied.id(),
            supplied.revision(),
            supplied.kind(),
            bounded(supplied.title(), 500).replaceAll("[\\p{Cntrl}]", " "),
            bounded(supplied.text(), 512),
            supplied.status(),
            supplied.occurred() == null ? clock.instant() : supplied.occurred(),
            supplied.due(),
            supplied.actionable(),
            arguments(supplied.data()));
    String destination = destination(c.config().projectId());
    database.transaction(
        db -> {
          var current = findConnection(db, c.config().projectId(), c.id());
          if (!current.config().enabled() || current.version() != c.version()) return null;
          String task = null;
          boolean existing = false;
          try (var q =
              db.prepareStatement(
                  "SELECT revision,task FROM secretary_external_items WHERE connection=? AND id=?")) {
            q.setString(1, c.id());
            q.setString(2, item.id());
            try (var r = q.executeQuery()) {
              if (r.next()) {
                existing = true;
                if (r.getString(1).equals(item.revision())) return null;
                task = r.getString(2);
              }
            }
          }
          if (!existing)
            try (var q =
                db.prepareStatement(
                    "SELECT COUNT(*) FROM secretary_external_items WHERE connection=?")) {
              q.setString(1, c.id());
              try (var r = q.executeQuery()) {
                r.next();
                if (r.getInt(1) >= 5000)
                  throw new IllegalStateException("External cache capacity reached");
              }
            }
          if (task == null && item.actionable() && c.config().importCandidates())
            task =
                ledger
                    .externalCandidateWithin(
                        db,
                        c.config().projectId(),
                        "external-candidate:" + c.id() + ":" + hash(item.id()),
                        bounded(c.config().service() + ": " + item.title(), 900),
                        item.due(),
                        reference(c.id(), item.id()))
                    .id();
          try (var q =
              db.prepareStatement(
                  "INSERT INTO secretary_external_items VALUES(?,?,?,?,?,?,?) ON CONFLICT(connection,id) DO UPDATE SET revision=excluded.revision,record=excluded.record,task=excluded.task,updated=excluded.updated")) {
            q.setString(1, c.id());
            q.setString(2, c.config().projectId());
            q.setString(3, item.id());
            q.setString(4, item.revision());
            q.setString(5, encode(item));
            q.setString(6, task);
            q.setLong(7, clock.millis());
            q.executeUpdate();
          }
          int inserted;
          try (var q =
              db.prepareStatement(
                  "INSERT OR IGNORE INTO secretary_external_events(connection,project,item,revision,kind,at) VALUES(?,?,?,?,?,?)")) {
            q.setString(1, c.id());
            q.setString(2, c.config().projectId());
            q.setString(3, item.id());
            q.setString(4, item.revision());
            q.setString(5, item.kind());
            q.setLong(6, clock.millis());
            inserted = q.executeUpdate();
          }
          if (inserted > 0)
            SecretaryOrchestrator.queue(
                db,
                new SecretaryNotifications.Notice(
                    uuid(c.id() + ":" + item.id() + ":" + item.revision()),
                    c.config().projectId(),
                    null,
                    "EXTERNAL_UPDATE",
                    "External evidence updated: "
                        + c.config().service()
                        + " / "
                        + bounded(item.title(), 300)
                        + " (untrusted content)",
                    3,
                    destination,
                    clock.instant()));
          return null;
        });
  }

  /** Accept only facts already authenticated by the owning webhook service. */
  public void ingestSigned(
      String project,
      String session,
      SecretaryProvider.Service service,
      String resource,
      SecretaryProvider.Item item) {
    var source = provider(service);
    if (source == null || source.status(service).state().equals("DISABLED")) return;
    for (var c : list(project)) {
      if (c.config().enabled()
          && c.config().service() == service
          && c.config().sessionId().equals(session)
          && c.config().resource().equalsIgnoreCase(resource)) ingest(c, item);
    }
  }

  public List<SecretaryProvider.Item> items(String project, String id, int limit) {
    connection(project, id);
    limit(limit);
    return database.read(
        db -> {
          var values = new ArrayList<SecretaryProvider.Item>();
          try (var q =
              db.prepareStatement(
                  "SELECT record FROM secretary_external_items WHERE project=? AND connection=? ORDER BY updated DESC,id LIMIT ?")) {
            q.setString(1, project);
            q.setString(2, id);
            q.setInt(3, limit);
            try (var r = q.executeQuery()) {
              while (r.next())
                values.add(
                    SecretaryJson.JSON.readValue(r.getString(1), SecretaryProvider.Item.class));
            }
          }
          return List.copyOf(values);
        });
  }

  public SecretaryTask toTask(String project, String id, String externalId) {
    var c = connection(project, id);
    return database.transaction(
        db -> {
          String task;
          SecretaryProvider.Item item;
          try (var q =
              db.prepareStatement(
                  "SELECT task,record FROM secretary_external_items WHERE project=? AND connection=? AND id=?")) {
            q.setString(1, project);
            q.setString(2, id);
            q.setString(3, externalId);
            try (var r = q.executeQuery()) {
              if (!r.next()) throw new IllegalArgumentException("External item not found");
              task = r.getString(1);
              item = SecretaryJson.JSON.readValue(r.getString(2), SecretaryProvider.Item.class);
            }
          }
          if (task != null) return ledger.getWithin(db, project, task);
          var candidate =
              ledger.externalCandidateWithin(
                  db,
                  project,
                  "external-candidate:" + id + ":" + hash(externalId),
                  bounded(c.config().service() + ": " + item.title(), 900),
                  item.due(),
                  reference(id, externalId));
          try (var q =
              db.prepareStatement(
                  "UPDATE secretary_external_items SET task=? WHERE project=? AND connection=? AND id=?")) {
            q.setString(1, candidate.id());
            q.setString(2, project);
            q.setString(3, id);
            q.setString(4, externalId);
            q.executeUpdate();
          }
          return candidate;
        });
  }

  public List<Event> history(String project, String id, int limit) {
    connection(project, id);
    limit(limit);
    return database.read(
        db -> {
          var values = new ArrayList<Event>();
          try (var q =
              db.prepareStatement(
                  "SELECT sequence,item,revision,kind,at FROM secretary_external_events WHERE project=? AND connection=? ORDER BY sequence DESC LIMIT ?")) {
            q.setString(1, project);
            q.setString(2, id);
            q.setInt(3, limit);
            try (var r = q.executeQuery()) {
              while (r.next())
                values.add(
                    new Event(
                        r.getLong(1),
                        r.getString(2),
                        r.getString(3),
                        r.getString(4),
                        Instant.ofEpochMilli(r.getLong(5))));
            }
          }
          return List.copyOf(values);
        });
  }

  private static boolean matches(SecretaryProvider.Service service, String operation) {
    return switch (service) {
      case CALENDAR -> operation.startsWith("CALENDAR_");
      case GMAIL -> operation.startsWith("MAIL_");
      case SLACK -> operation.startsWith("SLACK_");
      case GITHUB -> false;
    };
  }

  public synchronized Action prepare(
      String project,
      String connectionId,
      String receipt,
      String operation,
      Map<String, String> supplied) {
    identifier(project, 128);
    identifier(receipt, 256);
    identifier(connectionId, 128);
    identifier(operation, 64);
    var arguments = arguments(supplied);
    if (!WRITES.containsKey(operation) || !WRITES.get(operation).containsAll(arguments.keySet()))
      throw new IllegalArgumentException("Unknown external action/arguments");
    var mandatory =
        switch (operation) {
          case "CALENDAR_CREATE" -> Set.of("title", "start", "end");
          case "CALENDAR_UPDATE", "CALENDAR_CANCEL" -> Set.of("id", "etag");
          case "MAIL_DRAFT", "MAIL_SEND" -> Set.of("to", "subject", "body");
          case "SLACK_POST" -> Set.of("body");
          case "SLACK_REPORT" -> Set.of("taskId", "taskRevision");
          default -> Set.<String>of();
        };
    for (var field : mandatory)
      if (arguments.getOrDefault(field, "").isBlank())
        throw new IllegalArgumentException("Missing external argument: " + field);
    if (arguments.containsKey("trackReply")
        && !Set.of("true", "false").contains(arguments.get("trackReply")))
      throw new IllegalArgumentException("trackReply must be true or false");
    for (var header : List.of("to", "cc", "subject", "inReplyTo", "references"))
      if (arguments.getOrDefault(header, "").chars().anyMatch(Character::isISOControl))
        throw new IllegalArgumentException("Control characters in mail headers");
    String fingerprint = hash(canonical(List.of(connectionId, operation, arguments)));
    var existing = database.read(db -> receipt(db, project, receipt));
    if (existing != null) {
      if (!existing.spec().fingerprint().equals(fingerprint))
        throw new SecretaryConflictException();
      return existing;
    }
    var c = connection(project, connectionId);
    if (!matches(c.config().service(), operation))
      throw new IllegalArgumentException("Action/service mismatch");
    var source = usable(c);
    var authority = policy(operation);
    String identity =
        authority.enabled() && !authority.decision().equals("DENY")
            ? source.identity(c)
            : "not-admitted";
    identifier(identity, 256);
    if (operation.equals("SLACK_REPORT")) {
      var task = ledger.get(project, arguments.get("taskId"));
      if (task.revision() != Long.parseLong(arguments.getOrDefault("taskRevision", "-1")))
        throw new SecretaryConflictException();
      var copy = new java.util.HashMap<>(arguments);
      copy.put("body", "[れい 自動報告]\n" + task.title() + "\n状態: " + task.status());
      arguments = Map.copyOf(copy);
    }
    if (operation.startsWith("SLACK_")) {
      var copy = new java.util.HashMap<>(arguments);
      String body =
          "[れい]\n"
              + copy.getOrDefault("body", "")
              + "\n[rei-action:"
              + uuid(project + ":" + receipt)
              + "]";
      if (body.length() > 3500) throw new IllegalArgumentException("Slack body too long");
      copy.put("body", body);
      arguments = Map.copyOf(copy);
    }
    var spec =
        new Spec(
            project,
            connectionId,
            c.version(),
            c.config(),
            receipt,
            operation,
            arguments,
            fingerprint,
            identity,
            authority.fingerprint(),
            clock.instant().plusSeconds(900));
    String id = uuid(project + ":" + receipt);
    var created =
        database.transaction(
            db -> {
              var repeated = receipt(db, project, receipt);
              if (repeated != null) {
                if (!repeated.spec().fingerprint().equals(fingerprint))
                  throw new SecretaryConflictException();
                return repeated;
              }
              try (var q = db.createStatement();
                  var r =
                      q.executeQuery(
                          "SELECT COUNT(*) FROM secretary_external_actions WHERE state IN ('READY','WAITING_APPROVAL','SENDING','RETRY_WAIT')")) {
                r.next();
                if (r.getInt(1) >= 256)
                  throw new IllegalStateException("External action capacity reached");
              }
              var action =
                  new Action(
                      id,
                      spec,
                      "READY",
                      0,
                      null,
                      null,
                      0,
                      clock.instant(),
                      null,
                      "",
                      clock.instant(),
                      clock.instant());
              saveAction(db, action, true);
              return action;
            });
    return grant(created, authority);
  }

  private String canonical(Action action) {
    return canonical(List.of(action.id(), action.spec()));
  }

  private Action grant(Action action, SecretaryExecutionPolicy.Decision authority) {
    if (!authority.enabled() || authority.decision().equals("DENY"))
      return changeAction(
          action, "BLOCKED", null, "policy_denied", null, null, action.attempts(), action.next());
    if (action.authorization() != null && action.authorization().startsWith("APPROVAL:"))
      return action;
    if (!action.spec().operation().equals("MAIL_SEND")
        && authority.decision().equals("AUTO_APPROVE")) return action;
    var source = optional(permissions);
    if (source == null)
      return changeAction(
          action,
          "BLOCKED",
          null,
          "approval_unavailable",
          null,
          null,
          action.attempts(),
          action.next());
    if (action.permit() == null) {
      String permit =
          source.requestSecretaryAction(
              action.spec().projectId(),
              "EXTERNAL_" + action.spec().operation(),
              canonical(action));
      return changeAction(
          action,
          "WAITING_APPROVAL",
          null,
          "approval_pending",
          permit,
          null,
          action.attempts(),
          action.next());
    }
    String status =
        source.secretaryActionStatus(
            action.spec().projectId(),
            action.permit(),
            "EXTERNAL_" + action.spec().operation(),
            canonical(action));
    if (status.equals("APPROVED")) return action;
    return status.equals("PENDING")
        ? postponeApproval(action)
        : changeAction(
            action,
            "BLOCKED",
            null,
            "approval_" + safe(status),
            action.permit(),
            null,
            action.attempts(),
            action.next());
  }

  private Action postponeApproval(Action old) {
    return database.transaction(
        db -> {
          var current = findAction(db, old.spec().projectId(), old.id());
          expected(current, old.revision());
          var next =
              new Action(
                  current.id(),
                  current.spec(),
                  current.state(),
                  current.revision(),
                  current.permit(),
                  current.authorization(),
                  current.attempts(),
                  clock.instant().plusSeconds(60),
                  current.result(),
                  current.reason(),
                  current.created(),
                  current.updated());
          try (var q =
              db.prepareStatement(
                  "UPDATE secretary_external_actions SET record=?,next=? WHERE id=? AND revision=?")) {
            q.setString(1, encode(next));
            q.setLong(2, next.next().toEpochMilli());
            q.setString(3, next.id());
            q.setLong(4, next.revision());
            q.executeUpdate();
          }
          return next;
        });
  }

  public Action execute(String project, String id, long revision) {
    var action = action(project, id);
    expected(action, revision);
    if (TERMINAL.contains(action.state()) || action.state().equals("SENDING")) return action;
    if (!Set.of("READY", "WAITING_APPROVAL", "RETRY_WAIT").contains(action.state())
        || !action.state().equals("WAITING_APPROVAL") && action.next().isAfter(clock.instant()))
      return action;
    if (!clock.instant().isBefore(action.spec().expires()))
      return changeAction(
          action,
          "EXPIRED",
          null,
          "operation_expired",
          action.permit(),
          action.authorization(),
          action.attempts(),
          action.next());
    var c = connection(project, action.spec().connectionId());
    if (!c.config().enabled() || c.version() != action.spec().connectionVersion())
      return changeAction(
          action,
          "BLOCKED",
          null,
          "connection_changed",
          action.permit(),
          null,
          action.attempts(),
          action.next());
    var authority = policy(action.spec().operation());
    if (!authority.fingerprint().equals(action.spec().policyFingerprint()))
      return changeAction(
          action,
          "BLOCKED",
          null,
          "policy_changed",
          action.permit(),
          null,
          action.attempts(),
          action.next());
    var allowed = grant(action, authority);
    if (TERMINAL.contains(allowed.state())) return allowed;
    var approvals = optional(permissions);
    boolean proof =
        allowed.authorization() != null && allowed.authorization().startsWith("APPROVAL:");
    boolean automatic =
        !allowed.spec().operation().equals("MAIL_SEND")
            && authority.decision().equals("AUTO_APPROVE");
    if (!proof
        && !automatic
        && (approvals == null
            || !approvals
                .secretaryActionStatus(
                    project,
                    allowed.permit(),
                    "EXTERNAL_" + allowed.spec().operation(),
                    canonical(allowed))
                .equals("APPROVED"))) return allowed;
    var source = usable(c);
    if (!source.identity(c).equals(allowed.spec().identity()))
      return changeAction(
          allowed,
          "BLOCKED",
          null,
          "credential_identity_changed",
          allowed.permit(),
          null,
          allowed.attempts(),
          allowed.next());
    var claim =
        database.transaction(
            db -> {
              var current = findAction(db, project, id);
              expected(current, allowed.revision());
              try (var q =
                  db.prepareStatement(
                      "SELECT COUNT(*) FROM secretary_external_actions WHERE state='SENDING' AND connection=?")) {
                q.setString(1, c.id());
                try (var r = q.executeQuery()) {
                  r.next();
                  if (r.getInt(1) > 0) return current;
                }
              }
              var latestConnection = findConnection(db, project, c.id());
              if (!latestConnection.config().enabled() || latestConnection.version() != c.version())
                throw new SecretaryConflictException();
              try (var q = db.createStatement();
                  var r =
                      q.executeQuery(
                          "SELECT COUNT(*) FROM secretary_external_actions WHERE state='SENDING'")) {
                r.next();
                if (r.getInt(1) >= 2) return current;
              }
              if (current.attempts() >= 3)
                return changed(
                    db,
                    current,
                    "LIMITED",
                    null,
                    "attempt_limit",
                    current.permit(),
                    current.authorization(),
                    current.attempts(),
                    current.next());
              return changed(
                  db,
                  current,
                  "SENDING",
                  null,
                  "",
                  current.permit(),
                  current.authorization(),
                  current.attempts() + 1,
                  clock.instant().plusSeconds(120));
            });
    if (!claim.state().equals("SENDING")) return claim;
    String authorization =
        proof ? claim.authorization() : automatic ? "POLICY:" + authority.fingerprint() : null;
    if (authorization == null) {
      if (!approvals.consumeSecretaryAction(
          project, claim.permit(), "EXTERNAL_" + claim.spec().operation(), canonical(claim)))
        return changeAction(
            claim,
            "BLOCKED",
            null,
            "approval_not_consumable",
            claim.permit(),
            null,
            claim.attempts(),
            claim.next());
      authorization = "APPROVAL:" + claim.permit();
    }
    var admitted =
        changeAction(
            claim,
            "SENDING",
            null,
            "",
            claim.permit(),
            authorization,
            claim.attempts(),
            claim.next());
    try {
      var latest = connection(project, c.id());
      if (!latest.config().enabled()
          || latest.version() != c.version()
          || !source.identity(latest).equals(admitted.spec().identity()))
        return changeAction(
            admitted,
            "BLOCKED",
            null,
            "connection_changed_before_dispatch",
            admitted.permit(),
            admitted.authorization(),
            admitted.attempts(),
            admitted.next());
      var result =
          source.write(latest, admitted.spec().operation(), admitted.spec().arguments(), id);
      if (!result.verified() || !result.state().equals("COMPLETED"))
        return changeAction(
            admitted,
            "UNCERTAIN",
            result,
            "effect_unverified",
            admitted.permit(),
            admitted.authorization(),
            admitted.attempts(),
            admitted.next());
      return changeAction(
          admitted,
          "COMPLETED",
          result,
          "effect_verified",
          admitted.permit(),
          admitted.authorization(),
          admitted.attempts(),
          admitted.next());
    } catch (SecretaryProvider.Failure failure) {
      String state =
          failure.uncertain()
              ? "UNCERTAIN"
              : failure.code().equals("RATE_LIMITED") && admitted.attempts() < 3
                  ? "RETRY_WAIT"
                  : "REJECTED";
      return changeAction(
          admitted,
          state,
          null,
          safe(failure.code()),
          admitted.permit(),
          admitted.authorization(),
          admitted.attempts(),
          clock.instant().plusSeconds(Math.max(60, failure.retrySeconds())));
    } catch (RuntimeException failure) {
      return changeAction(
          admitted,
          "UNCERTAIN",
          null,
          "effect_unconfirmed",
          admitted.permit(),
          admitted.authorization(),
          admitted.attempts(),
          admitted.next());
    }
  }

  public Action action(String project, String id) {
    identifier(project, 128);
    identifier(id, 128);
    var result = database.read(db -> findAction(db, project, id));
    if (result == null) throw new IllegalArgumentException("External action not found in Project");
    return result;
  }

  public List<Action> actions(String project, int limit) {
    identifier(project, 128);
    limit(limit);
    return database.read(
        db -> {
          var values = new ArrayList<Action>();
          try (var q =
              db.prepareStatement(
                  "SELECT record FROM secretary_external_actions WHERE project=? ORDER BY updated DESC,id LIMIT ?")) {
            q.setString(1, project);
            q.setInt(2, limit);
            try (var r = q.executeQuery()) {
              while (r.next())
                values.add(SecretaryJson.JSON.readValue(r.getString(1), Action.class));
            }
          }
          return List.copyOf(values);
        });
  }

  /** Read-only reconciliation never replays a send or changes its approved destination. */
  public Action verify(String project, String id, long revision) {
    var action = action(project, id);
    expected(action, revision);
    if (!action.state().equals("UNCERTAIN")) return action;
    var c = connection(project, action.spec().connectionId());
    readAllowed();
    if (!c.config().enabled() || c.version() != action.spec().connectionVersion())
      throw new SecretaryConflictException();
    var source = usable(c);
    if (!source.identity(c).equals(action.spec().identity()))
      throw new SecretaryConflictException();
    var result =
        source.verify(c, action.spec().operation(), action.spec().arguments(), id, action.result());
    if (!result.verified() || !result.state().equals("COMPLETED")) return action;
    return changeAction(
        action,
        "COMPLETED",
        result,
        "effect_reconciled_read_only",
        action.permit(),
        action.authorization(),
        action.attempts(),
        action.next());
  }

  public Action cancel(String project, String id, long revision) {
    var action = action(project, id);
    expected(action, revision);
    if (TERMINAL.contains(action.state()) || action.state().equals("SENDING")) return action;
    return changeAction(
        action,
        "CANCELLED",
        null,
        "user_cancelled",
        action.permit(),
        action.authorization(),
        action.attempts(),
        action.next());
  }

  public List<ActionHistory> actionHistory(String project, String id, int limit) {
    action(project, id);
    limit(limit);
    return database.read(
        db -> {
          var values = new ArrayList<ActionHistory>();
          try (var q =
              db.prepareStatement(
                  "SELECT sequence,state,reason,at FROM secretary_external_action_history WHERE project=? AND action=? ORDER BY sequence DESC LIMIT ?")) {
            q.setString(1, project);
            q.setString(2, id);
            q.setInt(3, limit);
            try (var r = q.executeQuery()) {
              while (r.next())
                values.add(
                    new ActionHistory(
                        r.getLong(1),
                        r.getString(2),
                        r.getString(3),
                        Instant.ofEpochMilli(r.getLong(4))));
            }
          }
          return List.copyOf(values);
        });
  }

  private static void expected(Action action, long revision) {
    if (action == null || action.revision() != revision || revision < 0)
      throw new SecretaryConflictException();
  }

  private Action changeAction(
      Action expected,
      String state,
      SecretaryProvider.Outcome result,
      String reason,
      String permit,
      String authorization,
      int attempts,
      Instant next) {
    return database.transaction(
        db -> {
          var current = findAction(db, expected.spec().projectId(), expected.id());
          expected(current, expected.revision());
          return changed(db, current, state, result, reason, permit, authorization, attempts, next);
        });
  }

  private Action changed(
      java.sql.Connection db,
      Action old,
      String state,
      SecretaryProvider.Outcome result,
      String reason,
      String permit,
      String authorization,
      int attempts,
      Instant next)
      throws java.sql.SQLException, java.io.IOException {
    var updated =
        new Action(
            old.id(),
            old.spec(),
            state,
            old.revision() + 1,
            permit,
            authorization,
            attempts,
            next,
            result,
            safe(reason),
            old.created(),
            clock.instant());
    if (state.equals("COMPLETED")
        && old.spec().operation().equals("MAIL_SEND")
        && !"false".equals(old.spec().arguments().get("trackReply"))
        && result != null) {
      String thread = result.data().get("threadId");
      if (thread != null && !thread.isBlank()) {
        var task =
            ledger.externalWaitWithin(
                db,
                old.spec().projectId(),
                "mail-sent:" + old.id(),
                bounded("返信待ち: " + old.spec().arguments().getOrDefault("subject", "mail"), 500),
                reference(old.spec().connectionId(), thread));
        try (var q =
            db.prepareStatement(
                "INSERT OR IGNORE INTO secretary_external_waits(connection,project,thread,task,sent) VALUES(?,?,?,?,?)")) {
          q.setString(1, old.spec().connectionId());
          q.setString(2, old.spec().projectId());
          q.setString(3, thread);
          q.setString(4, task.id());
          q.setLong(5, clock.millis());
          q.executeUpdate();
        }
      }
    }
    saveAction(db, updated, false);
    if (TERMINAL.contains(state) || state.equals("WAITING_APPROVAL"))
      SecretaryOrchestrator.queue(
          db,
          new SecretaryNotifications.Notice(
              uuid("external-action:" + updated.id() + ":" + updated.revision()),
              updated.spec().projectId(),
              null,
              "EXTERNAL_RESULT",
              state
                  + ": /secretary external-action "
                  + updated.id()
                  + (state.equals("WAITING_APPROVAL") ? "; /approval approve " + permit : ""),
              3,
              destination(updated.spec().projectId()),
              clock.instant()));
    return updated;
  }

  private static void saveAction(java.sql.Connection db, Action action, boolean insert)
      throws java.sql.SQLException, java.io.IOException {
    if (insert)
      try (var q =
          db.prepareStatement("INSERT INTO secretary_external_actions VALUES(?,?,?,?,?,?,?,?,?)")) {
        q.setString(1, action.id());
        q.setString(2, action.spec().projectId());
        q.setString(3, action.spec().connectionId());
        q.setString(4, action.spec().receipt());
        q.setString(5, encode(action));
        q.setString(6, action.state());
        q.setLong(7, action.revision());
        q.setLong(8, action.next().toEpochMilli());
        q.setLong(9, action.updated().toEpochMilli());
        q.executeUpdate();
      }
    else
      try (var q =
          db.prepareStatement(
              "UPDATE secretary_external_actions SET record=?,state=?,revision=?,next=?,updated=? WHERE project=? AND id=? AND revision=?")) {
        q.setString(1, encode(action));
        q.setString(2, action.state());
        q.setLong(3, action.revision());
        q.setLong(4, action.next().toEpochMilli());
        q.setLong(5, action.updated().toEpochMilli());
        q.setString(6, action.spec().projectId());
        q.setString(7, action.id());
        q.setLong(8, action.revision() - 1);
        if (q.executeUpdate() != 1) throw new SecretaryConflictException();
      }
    try (var q =
        db.prepareStatement(
            "INSERT INTO secretary_external_action_history(project,action,state,reason,at) VALUES(?,?,?,?,?)")) {
      q.setString(1, action.spec().projectId());
      q.setString(2, action.id());
      q.setString(3, action.state());
      q.setString(4, action.reason());
      q.setLong(5, action.updated().toEpochMilli());
      q.executeUpdate();
    }
  }

  private static Action findAction(java.sql.Connection db, String project, String id)
      throws java.sql.SQLException, java.io.IOException {
    try (var q =
        db.prepareStatement(
            "SELECT record FROM secretary_external_actions WHERE project=? AND id=?")) {
      q.setString(1, project);
      q.setString(2, id);
      try (var r = q.executeQuery()) {
        return r.next() ? SecretaryJson.JSON.readValue(r.getString(1), Action.class) : null;
      }
    }
  }

  private static Action receipt(java.sql.Connection db, String project, String receipt)
      throws java.sql.SQLException, java.io.IOException {
    try (var q =
        db.prepareStatement(
            "SELECT record FROM secretary_external_actions WHERE project=? AND receipt=?")) {
      q.setString(1, project);
      q.setString(2, receipt);
      try (var r = q.executeQuery()) {
        return r.next() ? SecretaryJson.JSON.readValue(r.getString(1), Action.class) : null;
      }
    }
  }

  private static Connection findConnection(java.sql.Connection db, String project, String id)
      throws java.sql.SQLException, java.io.IOException {
    try (var q =
        db.prepareStatement("SELECT record FROM secretary_connections WHERE project=? AND id=?")) {
      q.setString(1, project);
      q.setString(2, id);
      try (var r = q.executeQuery()) {
        return r.next() ? SecretaryJson.JSON.readValue(r.getString(1), Connection.class) : null;
      }
    }
  }

  private static void saveConnection(java.sql.Connection db, Connection value)
      throws java.sql.SQLException, java.io.IOException {
    try (var q =
        db.prepareStatement(
            "INSERT INTO secretary_connections VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET version=excluded.version,record=excluded.record,next=excluded.next,state=excluded.state,updated=excluded.updated")) {
      q.setString(1, value.id());
      q.setString(2, value.config().projectId());
      q.setString(3, value.config().name());
      q.setString(4, value.config().service().name());
      q.setLong(5, value.version());
      q.setString(6, encode(value));
      q.setLong(7, value.next().toEpochMilli());
      q.setString(8, value.state());
      q.setLong(9, value.updated().toEpochMilli());
      q.executeUpdate();
    }
  }

  public void check() {
    var pending =
        database.read(
            db -> {
              var values = new ArrayList<Action>();
              try (var q =
                  db.prepareStatement(
                      "SELECT record FROM secretary_external_actions WHERE state IN ('READY','WAITING_APPROVAL','RETRY_WAIT','SENDING') AND next<=? ORDER BY CASE state WHEN 'READY' THEN 0 WHEN 'RETRY_WAIT' THEN 1 WHEN 'SENDING' THEN 2 ELSE 3 END,next,id LIMIT 8")) {
                q.setLong(1, clock.millis());
                try (var r = q.executeQuery()) {
                  while (r.next())
                    values.add(SecretaryJson.JSON.readValue(r.getString(1), Action.class));
                }
              }
              return values;
            });
    for (var action : pending)
      try {
        if (action.state().equals("SENDING"))
          changeAction(
              action,
              "UNCERTAIN",
              null,
              "interrupted_effect_unconfirmed",
              action.permit(),
              action.authorization(),
              action.attempts(),
              action.next());
        else execute(action.spec().projectId(), action.id(), action.revision());
      } catch (RuntimeException unavailable) {
        /* Each action fails independently. */
      }
    if (!automatic) return;
    var due =
        database.read(
            db -> {
              var values = new ArrayList<Connection>();
              try (var q =
                  db.prepareStatement(
                      "SELECT record FROM secretary_connections WHERE state<>'DISABLED' AND next<=? ORDER BY next,id LIMIT 8")) {
                q.setLong(1, clock.millis());
                try (var r = q.executeQuery()) {
                  while (r.next())
                    values.add(SecretaryJson.JSON.readValue(r.getString(1), Connection.class));
                }
              }
              return values;
            });
    for (var c : due) {
      if (Thread.currentThread().isInterrupted()) return;
      if (life == null || life.backgroundAllowed(c.config().projectId(), "connection:" + c.id()))
        pollNow(c.config().projectId(), c.id());
    }
  }

  @Override
  public List<Item> context(String project, LocalDate date, ZoneId zone) {
    identifier(project, 128);
    return database.read(
        db -> {
          var values = new ArrayList<Item>();
          try (var q =
              db.prepareStatement(
                  "SELECT i.connection,i.id,i.record FROM secretary_external_items i JOIN secretary_connections c ON c.id=i.connection WHERE i.project=? AND c.state<>'DISABLED' AND (c.service='CALENDAR' OR (i.updated>=? AND i.updated<?)) ORDER BY i.updated DESC,i.id LIMIT 256")) {
            q.setString(1, project);
            q.setLong(2, date.atStartOfDay(zone).toInstant().toEpochMilli());
            q.setLong(3, date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli());
            try (var r = q.executeQuery()) {
              while (r.next()) {
                var item =
                    SecretaryJson.JSON.readValue(r.getString(3), SecretaryProvider.Item.class);
                if (item.kind().equals("CALENDAR")) {
                  try {
                    var start = java.time.ZonedDateTime.parse(item.data().get("start")).toInstant();
                    var end = java.time.ZonedDateTime.parse(item.data().get("end")).toInstant();
                    if (!start.isBefore(date.plusDays(1).atStartOfDay(zone).toInstant())
                        || !end.isAfter(date.atStartOfDay(zone).toInstant())) continue;
                  } catch (RuntimeException noTimes) {
                    if (!item.occurred().atZone(zone).toLocalDate().equals(date)) continue;
                  }
                }
                values.add(
                    new Item(
                        "external-untrusted",
                        reference(r.getString(1), r.getString(2)),
                        "[UNTRUSTED] " + item.title(),
                        item.status(),
                        "External body omitted; use an explicit connection read for details."));
              }
            }
          }
          return List.copyOf(values);
        });
  }

  @Override
  public void tick() {
    if (closed.get() || !busy.compareAndSet(false, true)) return;
    try {
      worker.execute(
          () -> {
            try {
              check();
            } finally {
              busy.set(false);
            }
          });
    } catch (java.util.concurrent.RejectedExecutionException rejected) {
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
