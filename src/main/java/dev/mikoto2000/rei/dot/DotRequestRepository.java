package dev.mikoto2000.rei.dot;

import dev.mikoto2000.rei.storage.StorageDatabase;
import java.nio.file.Path;
import java.sql.*;
import java.time.*;
import java.util.*;

/** One serialized application writer. Receipt and resume intent commit together. */
public class DotRequestRepository {
  private final StorageDatabase db;
  private final Clock clock;

  private static boolean parentAvailable(Connection c, Owner owner) throws SQLException {
    if (owner.run() == null) return true;
    return count(
            c,
            "SELECT count(*) FROM conversation_admissions WHERE run_id=? AND status='WAITING' AND"
                + " json_extract(context,'$.runId')=? AND json_extract(context,'$.projectId')=? AND"
                + " json_extract(context,'$.sessionId')=? AND json_extract(context,'$.root')=? AND"
                + " json_extract(context,'$.mode')='EXCLUSIVE' AND"
                + " json_extract(context,'$.source')!='MCP' AND"
                + " json_extract(context,'$.voiceInput')=0",
            owner.run(),
            owner.run(),
            owner.project(),
            owner.session(),
            owner.root())
        == 1;
  }

  private int activeLimit = 2, queueLimit = 8;
  private Duration lease = Duration.ofMinutes(30),
      resultRetention = Duration.ofDays(30),
      requestRetention = Duration.ofDays(90);

  public DotRequestRepository(Path root, Clock clock) {
    db = new StorageDatabase(root);
    this.clock = clock;
  }

  public void limits(int active, int queue, Duration lease, Duration result, Duration requests) {
    activeLimit = active;
    queueLimit = queue;
    this.lease = lease;
    resultRetention = result;
    requestRetention = requests;
  }

  public record Owner(
      String id,
      String project,
      String session,
      String run,
      String root,
      String task,
      String dependency,
      String status,
      long expires,
      String validation) {}

  private static String json(Object value) {
    try {
      return StorageDatabase.JSON.writeValueAsString(value);
    } catch (Exception e) {
      throw new DotException("INVALID_ARGUMENT");
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> document(String text) {
    try {
      return text == null ? Map.of() : StorageDatabase.JSON.readValue(text, Map.class);
    } catch (Exception e) {
      throw new IllegalStateException("Invalid saved Dot document");
    }
  }

  private static int update(Connection c, String sql, Object... values) throws SQLException {
    try (var s = c.prepareStatement(sql)) {
      for (int i = 0; i < values.length; i++) s.setObject(i + 1, values[i]);
      return s.executeUpdate();
    }
  }

  private static long count(Connection c, String sql, Object... values) throws SQLException {
    try (var s = c.prepareStatement(sql)) {
      for (int i = 0; i < values.length; i++) s.setObject(i + 1, values[i]);
      try (var r = s.executeQuery()) {
        r.next();
        return r.getLong(1);
      }
    }
  }

  private static Owner owner(Connection c, String project, String id) throws SQLException {
    try (var s = c.prepareStatement("SELECT * FROM dot_requests WHERE id=? AND project=?")) {
      s.setString(1, id);
      s.setString(2, project);
      try (var r = s.executeQuery()) {
        if (!r.next()) throw new DotException("NOT_FOUND");
        return new Owner(
            id,
            project,
            r.getString("session"),
            r.getString("run"),
            r.getString("root"),
            r.getString("task"),
            r.getString("dependency"),
            r.getString("status"),
            r.getLong("expires"),
            r.getString("validation"));
      }
    }
  }

  public Owner owner(String project, String id) {
    maintain();
    return db.read(c -> owner(c, project, id));
  }

  public byte[] cursorKey() {
    return db.read(
        c -> {
          try (var s = c.createStatement();
              var r = s.executeQuery("SELECT value FROM mcp_settings WHERE name='cursor_key'")) {
            if (!r.next()) throw new IllegalStateException("Missing cursor key");
            return HexFormat.of().parseHex(r.getString(1));
          }
        });
  }

  public Optional<String> project(String id) {
    return db.read(
        c -> {
          try (var s = c.prepareStatement("SELECT project FROM dot_requests WHERE id=?")) {
            s.setString(1, id);
            try (var r = s.executeQuery()) {
              return r.next() ? Optional.of(r.getString(1)) : Optional.empty();
            }
          }
        });
  }

  private void audit(
      Connection c, String id, String project, String actor, String operation, String outcome)
      throws SQLException {
    update(
        c,
        "INSERT INTO dot_audit VALUES(?,?,?,?,?,?,?)",
        UUID.randomUUID().toString(),
        id,
        project,
        actor,
        operation,
        outcome,
        clock.millis());
  }

  public void audit(String project, String id, String actor, String operation, String outcome) {
    db.transaction(
        c -> {
          audit(c, id, project, actor, operation, outcome);
          return null;
        });
  }

  public Map<String, Object> create(
      String project,
      String session,
      String run,
      String root,
      Map<String, Object> publication,
      Duration timeout) {
    return create(project, session, run, root, publication, timeout, null);
  }

  public Map<String, Object> create(
      String project,
      String session,
      String run,
      String root,
      Map<String, Object> publication,
      Duration timeout,
      String requestId) {
    if (timeout == null
        || timeout.compareTo(Duration.ofSeconds(1)) < 0
        || timeout.compareTo(Duration.ofHours(24)) > 0) throw new DotException("INVALID_ARGUMENT");
    maintain();
    String id =
        requestId == null
            ? UUID.randomUUID().toString()
            : DotPayload.id(Map.of("request_id", requestId), "request_id");
    String fingerprint =
        dev.mikoto2000.rei.conversation.ConversationAdmissionStore.hash(
            json(Arrays.asList(project, session, run, root, publication, timeout.toMillis())));
    return db.transaction(
        c -> {
          try (var query =
              c.prepareStatement(
                  "SELECT project,creation_fingerprint FROM dot_requests WHERE id=?")) {
            query.setString(1, id);
            try (var existing = query.executeQuery()) {
              if (existing.next()) {
                if (!project.equals(existing.getString(1))
                    || !fingerprint.equals(existing.getString(2)))
                  throw new DotException("REQUEST_CONFLICT");
                audit(c, id, project, "human", "CREATE", "REPLAYED");
                return get(c, project, id);
              }
            }
          }
          if (count(
                  c,
                  "SELECT count(*) FROM dot_requests WHERE status IN"
                      + " ('PENDING','CLAIMED','RESULT_RECEIVED','UNKNOWN')")
              >= activeLimit + queueLimit) throw new DotException("CAPACITY_EXCEEDED");
          if (run != null && count(c, "SELECT count(*) FROM dot_requests WHERE run=?", run) > 0)
            throw new DotException("DELEGATION_ALREADY_USED");
          update(
              c,
              "INSERT INTO"
                  + " dot_requests(id,project,session,run,root,publication,status,created,expires,published,creation_fingerprint)"
                  + " VALUES(?,?,?,?,?,?,'PENDING',?,?,?,?)",
              id,
              project,
              session,
              run,
              root,
              json(publication),
              clock.millis(),
              clock.instant().plus(timeout).toEpochMilli(),
              run == null ? 1 : 0,
              fingerprint);
          audit(c, id, project, run == null ? "human" : "parent-llm", "CREATE", "PENDING");
          return get(c, project, id);
        });
  }

  public List<Map<String, Object>> list(
      Set<String> projects, String status, String after, int limit) {
    if (limit < 1
        || limit > 20
        || status == null
        || !Set.of(
                "PENDING",
                "CLAIMED",
                "RESULT_RECEIVED",
                "VERIFIED",
                "PARTIALLY_VERIFIED",
                "REJECTED",
                "FAILED",
                "CANCELLED",
                "EXPIRED",
                "UNKNOWN")
            .contains(status)) throw new DotException("INVALID_ARGUMENT");
    maintain();
    if (projects.isEmpty()) return List.of();
    return db.read(
        c -> {
          var out = new ArrayList<Map<String, Object>>();
          var slots = String.join(",", Collections.nCopies(projects.size(), "?"));
          try (var s =
              c.prepareStatement(
                  "SELECT id,project,publication,status,created,expires FROM dot_requests WHERE"
                      + " project IN ("
                      + slots
                      + ") AND status=? AND id>? AND publication IS NOT NULL AND published=1 ORDER"
                      + " BY id LIMIT ?")) {
            int index = 1;
            for (String p : new TreeSet<>(projects)) s.setString(index++, p);
            s.setString(index++, status);
            s.setString(index++, after == null ? "" : after);
            s.setInt(index, limit);
            try (var r = s.executeQuery()) {
              while (r.next())
                out.add(
                    Map.of(
                        "request_id",
                        r.getString("id"),
                        "project_id",
                        r.getString("project"),
                        "category",
                        document(r.getString("publication")).get("category"),
                        "status",
                        r.getString("status"),
                        "created_at",
                        Instant.ofEpochMilli(r.getLong("created")).toString(),
                        "expires_at",
                        Instant.ofEpochMilli(r.getLong("expires")).toString()));
            }
          }
          return List.copyOf(out);
        });
  }

  private Map<String, Object> get(Connection c, String project, String id) throws SQLException {
    owner(c, project, id);
    try (var s =
        c.prepareStatement(
            "SELECT q.*,i.status AS resume_status,i.run AS resume_run FROM dot_requests q LEFT JOIN"
                + " dot_resume_intents i ON q.id=i.request WHERE q.id=? AND q.project=?")) {
      s.setString(1, id);
      s.setString(2, project);
      try (var r = s.executeQuery()) {
        r.next();
        if (r.getString("publication") == null) throw new DotException("REQUEST_EXPIRED");
        var out = new LinkedHashMap<String, Object>(document(r.getString("publication")));
        out.put("schema_version", 1);
        out.put("request_id", id);
        out.put("project_id", project);
        out.put("status", r.getString("status"));
        out.put("created_at", Instant.ofEpochMilli(r.getLong("created")).toString());
        out.put("expires_at", Instant.ofEpochMilli(r.getLong("expires")).toString());
        out.put("validation_status", r.getString("validation"));
        out.put("resume_status", Objects.toString(r.getString("resume_status"), "NOT_REQUESTED"));
        return out;
      }
    }
  }

  public Map<String, Object> get(String project, String id) {
    maintain();
    return db.read(c -> get(c, project, id));
  }

  public Map<String, Object> claim(String project, String id, String claim) {
    maintain();
    return db.transaction(
        c -> {
          var o = owner(c, project, id);
          try (var s = c.prepareStatement("SELECT claim,claim_json FROM dot_requests WHERE id=?")) {
            s.setString(1, id);
            try (var r = s.executeQuery()) {
              r.next();
              if (claim.equals(r.getString(1))) return document(r.getString(2));
              if (r.getString(1) != null) throw new DotException("CLAIM_CONFLICT");
            }
          }
          if (count(c, "SELECT count(*) FROM dot_requests WHERE id=? AND published=1", id) != 1)
            throw new DotException("NOT_FOUND");
          if (!parentAvailable(c, o)) throw new DotException("PARENT_NOT_WAITING");
          if (!o.status().equals("PENDING") || o.expires() <= clock.millis())
            throw new DotException("REQUEST_NOT_PENDING");
          if (count(
                  c,
                  "SELECT count(*) FROM dot_requests WHERE status IN"
                      + " ('CLAIMED','RESULT_RECEIVED','UNKNOWN')")
              >= activeLimit) throw new DotException("CAPACITY_EXCEEDED");
          long deadline = Math.min(o.expires(), clock.instant().plus(lease).toEpochMilli());
          var response =
              Map.<String, Object>of(
                  "schema_version",
                  1,
                  "request_id",
                  id,
                  "claim_id",
                  claim,
                  "status",
                  "CLAIMED",
                  "lease_expires_at",
                  Instant.ofEpochMilli(deadline).toString());
          if (update(
                  c,
                  "UPDATE dot_requests SET claim=?,claim_json=?,lease=?,status='CLAIMED' WHERE id=?"
                      + " AND status='PENDING'",
                  claim,
                  json(response),
                  deadline,
                  id)
              != 1) throw new DotException("CLAIM_CONFLICT");
          audit(c, id, project, "local-mcp", "CLAIM", "CLAIMED");
          return response;
        });
  }

  public Map<String, Object> submit(
      String project,
      String id,
      String claim,
      String submission,
      Map<String, Object> result,
      String fingerprint) {
    maintain();
    return db.transaction(
        c -> {
          var o = owner(c, project, id);
          try (var s =
              c.prepareStatement(
                  "SELECT submission,fingerprint,receipt FROM dot_receipts WHERE request=?")) {
            s.setString(1, id);
            try (var r = s.executeQuery()) {
              if (r.next()) {
                if (submission.equals(r.getString(1)) && fingerprint.equals(r.getString(2)))
                  return document(r.getString(3));
                throw new DotException("SUBMISSION_CONFLICT");
              }
            }
          }
          try (var s = c.prepareStatement("SELECT claim,lease FROM dot_requests WHERE id=?")) {
            s.setString(1, id);
            try (var r = s.executeQuery()) {
              r.next();
              if (!claim.equals(r.getString(1))) throw new DotException("CLAIM_MISMATCH");
              if (r.getLong(2) <= clock.millis()) throw new DotException("LATE_RESULT");
            }
          }
          if (!parentAvailable(c, o)) throw new DotException("PARENT_NOT_WAITING");
          if (!o.status().equals("CLAIMED") || o.expires() <= clock.millis())
            throw new DotException("LATE_RESULT");
          if (count(c, "SELECT count(*) FROM dot_receipts WHERE submission=?", submission) > 0)
            throw new DotException("SUBMISSION_CONFLICT");
          var receipt =
              Map.<String, Object>of(
                  "schema_version",
                  1,
                  "request_id",
                  id,
                  "submission_id",
                  submission,
                  "receipt_id",
                  UUID.randomUUID().toString(),
                  "persisted",
                  true,
                  "validation_status",
                  "PENDING_REVIEW",
                  "resume_status",
                  "NOT_REQUESTED");
          update(
              c,
              "INSERT INTO dot_receipts VALUES(?,?,?,?,?,?)",
              id,
              submission,
              fingerprint,
              json(result),
              json(receipt),
              clock.millis());
          update(
              c,
              "INSERT INTO dot_resume_intents(request,status,run,updated)"
                  + " VALUES(?,'NOT_REQUESTED',NULL,?)",
              id,
              clock.millis());
          update(
              c,
              "UPDATE dot_requests SET status='RESULT_RECEIVED',validation='PENDING_REVIEW' WHERE"
                  + " id=?",
              id);
          audit(c, id, project, "local-mcp", "RESULT_SUBMIT", "PERSISTED");
          return receipt;
        });
  }

  public Map<String, Object> cancel(String project, String id) {
    maintain();
    return db.transaction(
        c -> {
          var o = owner(c, project, id);
          if (Set.of("VERIFIED", "PARTIALLY_VERIFIED", "REJECTED", "FAILED", "EXPIRED")
              .contains(o.status())) throw new DotException("REQUEST_TERMINAL");
          if (!o.status().equals("CANCELLED")) {
            update(c, "UPDATE dot_requests SET status='CANCELLED' WHERE id=?", id);
            audit(c, id, project, "human", "CANCEL", "CANCELLED");
          }
          update(
              c,
              "UPDATE dot_resume_intents SET status='CANCELLED',updated=? WHERE request=? AND"
                  + " status IN ('NOT_REQUESTED','REQUESTED')",
              clock.millis(),
              id);
          return get(c, project, id);
        });
  }

  public Map<String, Object> result(String project, String id) {
    maintain();
    return db.read(
        c -> {
          var owner = owner(c, project, id);
          try (var s =
              c.prepareStatement("SELECT result,receipt FROM dot_receipts WHERE request=?")) {
            s.setString(1, id);
            try (var r = s.executeQuery()) {
              if (!r.next()) throw new DotException("RESULT_NOT_READY");
              if (r.getString(1) == null) throw new DotException("RESULT_EXPIRED");
              var out = new LinkedHashMap<String, Object>(document(r.getString(1)));
              out.put("receipt", document(r.getString(2)));
              out.put("untrusted", true);
              out.put("validation_status", owner.validation());
              return out;
            }
          }
        });
  }

  public boolean published(String project, String id) {
    return db.read(
        c ->
            count(
                    c,
                    "SELECT count(*) FROM dot_requests WHERE id=? AND project=? AND published=1",
                    id,
                    project)
                == 1);
  }

  public Optional<Owner> byRun(String project, String run) {
    return db.read(
        c -> {
          try (var s =
              c.prepareStatement("SELECT id FROM dot_requests WHERE project=? AND run=?")) {
            s.setString(1, project);
            s.setString(2, run);
            try (var r = s.executeQuery()) {
              return r.next() ? Optional.of(owner(c, project, r.getString(1))) : Optional.empty();
            }
          }
        });
  }

  public void bind(
      String id, String project, String task, String dependency, Map<String, Object> authority) {
    db.transaction(
        c -> {
          owner(c, project, id);
          update(
              c,
              "UPDATE dot_requests SET task=?,dependency=?,authority=? WHERE id=? AND project=? AND"
                  + " published=0",
              task,
              dependency,
              json(authority),
              id,
              project);
          return null;
        });
  }

  public void publish(String project, String id) {
    db.transaction(
        c -> {
          var o = owner(c, project, id);
          if (o.task() == null || o.dependency() == null || !o.status().equals("PENDING"))
            throw new DotException("ADMISSION_UNKNOWN");
          update(c, "UPDATE dot_requests SET published=1 WHERE id=? AND project=?", id, project);
          audit(c, id, project, "parent-llm", "WAIT", "PUBLISHED");
          return null;
        });
  }

  public void abandonAdmission(String project, String id) {
    db.transaction(
        c -> {
          owner(c, project, id);
          update(
              c,
              "UPDATE dot_requests SET status='UNKNOWN',published=0 WHERE id=? AND project=?",
              id,
              project);
          return null;
        });
  }

  public void recoverAdmissions() {
    db.transaction(
        c -> {
          update(
              c, "UPDATE dot_requests SET status='UNKNOWN' WHERE published=0 AND status='PENDING'");
          return null;
        });
  }

  public void maintain() {
    db.transaction(
        c -> {
          long now = clock.millis();
          update(
              c,
              "UPDATE dot_requests SET status='UNKNOWN' WHERE status='CLAIMED' AND (lease<=? OR"
                  + " expires<=?)",
              now,
              now);
          update(
              c,
              "UPDATE dot_requests SET status='EXPIRED' WHERE status='PENDING' AND expires<=?",
              now);
          update(
              c,
              "UPDATE dot_receipts SET result=NULL WHERE received<=?",
              now - resultRetention.toMillis());
          update(
              c,
              "UPDATE dot_requests SET publication=NULL,review_note=NULL WHERE created<=? AND"
                  + " status IN"
                  + " ('VERIFIED','PARTIALLY_VERIFIED','REJECTED','FAILED','CANCELLED','EXPIRED')",
              now - requestRetention.toMillis());
          update(c, "DELETE FROM dot_audit WHERE created<=?", now - requestRetention.toMillis());
          return null;
        });
  }

  public Map<String, Object> review(
      String project, String id, String status, List<Integer> satisfied, String note) {
    maintain();
    return db.transaction(
        c -> {
          var o = owner(c, project, id);
          if (!Set.of("VERIFIED", "PARTIALLY_VERIFIED", "REJECTED", "FAILED").contains(status)
              || !Set.of("RESULT_RECEIVED", "PARTIALLY_VERIFIED").contains(o.status()))
            throw new DotException("REVIEW_NOT_ALLOWED");
          if (count(
                  c, "SELECT count(*) FROM dot_receipts WHERE request=? AND result IS NOT NULL", id)
              != 1) throw new DotException("RESULT_EXPIRED");
          var criteria = (List<?>) get(c, project, id).get("acceptance_criteria");
          var unique = new HashSet<Integer>(satisfied);
          if (unique.size() != satisfied.size()
              || unique.stream().anyMatch(n -> n < 0 || n >= criteria.size())
              || status.equals("VERIFIED") && unique.size() != criteria.size())
            throw new DotException("ACCEPTANCE_NOT_VERIFIED");
          String safe = DotPayload.safe(note == null ? "" : note);
          if (safe.isBlank()
              || safe.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 4096)
            throw new DotException("REVIEW_NOTE_REQUIRED");
          update(
              c,
              "UPDATE dot_requests SET status=?,validation=?,review_note=? WHERE id=?",
              status,
              status,
              json(Map.of("note", safe, "satisfied", satisfied)),
              id);
          audit(c, id, project, "human", "VERIFY", status);
          return get(c, project, id);
        });
  }

  public boolean requestResume(String project, String id, long revision) {
    maintain();
    return db.transaction(
        c -> {
          var o = owner(c, project, id);
          if (!o.status().equals("VERIFIED")
              || count(
                      c,
                      "SELECT count(*) FROM dot_receipts WHERE request=? AND result IS NOT NULL",
                      id)
                  != 1) throw new DotException("VERIFIED_RESULT_REQUIRED");
          if (update(
                  c,
                  "UPDATE dot_resume_intents SET status='REQUESTED',revision=?,updated=? WHERE"
                      + " request=? AND status='NOT_REQUESTED'",
                  revision,
                  clock.millis(),
                  id)
              == 1) {
            audit(c, id, project, "human", "RESUME", "REQUESTED");
            return true;
          }
          return false;
        });
  }

  public void attachSchedule(String project, String id, String schedule) {
    db.transaction(
        c -> {
          owner(c, project, id);
          if (update(
                  c,
                  "UPDATE dot_resume_intents SET schedule=?,updated=? WHERE request=? AND"
                      + " status='REQUESTED' AND schedule IS NULL",
                  schedule,
                  clock.millis(),
                  id)
              != 1) throw new DotException("RESUME_CONFLICT");
          return null;
        });
  }

  public boolean startResume(String project, String id, String schedule, String run) {
    return db.transaction(
        c -> {
          var o = owner(c, project, id);
          if (!o.status().equals("VERIFIED")) throw new DotException("VERIFIED_RESULT_REQUIRED");
          return update(
                  c,
                  "UPDATE dot_resume_intents SET status='STARTED',run=?,updated=? WHERE request=?"
                      + " AND schedule=? AND status='REQUESTED'",
                  run,
                  clock.millis(),
                  id,
                  schedule)
              == 1;
        });
  }

  public Map<String, Object> intent(String project, String id) {
    return db.read(
        c -> {
          owner(c, project, id);
          try (var s = c.prepareStatement("SELECT * FROM dot_resume_intents WHERE request=?")) {
            s.setString(1, id);
            try (var r = s.executeQuery()) {
              if (!r.next()) throw new DotException("RESULT_NOT_READY");
              var out = new LinkedHashMap<String, Object>();
              out.put("status", r.getString("status"));
              out.put("run", r.getString("run"));
              out.put("schedule", r.getString("schedule"));
              out.put("revision", r.getLong("revision"));
              return out;
            }
          }
        });
  }

  public void finishResume(String project, String id, String status) {
    db.transaction(
        c -> {
          owner(c, project, id);
          update(
              c,
              "UPDATE dot_resume_intents SET status=?,updated=? WHERE request=? AND status IN"
                  + " ('REQUESTED','STARTED')",
              status,
              clock.millis(),
              id);
          audit(c, id, project, "scheduler", "RESUME", status);
          return null;
        });
  }

  public void recoverResumeIntents() {
    db.transaction(
        c -> {
          update(
              c,
              "UPDATE dot_resume_intents SET status='UNKNOWN',updated=? WHERE status='STARTED' OR"
                  + " (status='REQUESTED' AND schedule IS NULL)",
              clock.millis());
          return null;
        });
  }
}
