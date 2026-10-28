package dev.mikoto2000.rei.durable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Work-level claims and frozen sources share the existing serialized SQLite writer. */
public final class WorkItemStore {
  public record Item(String id, String task, String snapshot, String path, int start, int end,
      String version, String kind, String criterion, String state, boolean required) {}

  public record Claim(Item item, String run, String attempt) {}

  public record Finding(String id, String severity, int start, int end, String evidence, String assessment) {}

  public record ReviewResult(String work, String snapshot, String path, int start, int end,
      String version, String criterion, String assessment, List<Finding> findings) {
    public ReviewResult {
      findings = List.copyOf(findings);
    }
  }

  public record Result(ReviewResult body, String sha256) {}

  private static final com.fasterxml.jackson.databind.ObjectMapper JSON = com.fasterxml.jackson.databind.json.JsonMapper.builder()
      .enable(com.fasterxml.jackson.databind.MapperFeature.SORT_PROPERTIES_ALPHABETICALLY).build();

  private final DurableTaskStore store;

  public WorkItemStore(DurableTaskStore store) {
    this.store = store;
  }

  /** Explicit recovery reuses durable results and revokes the previous Run's write authority. */
  public DurableTaskStore.Task resume(DurableTaskStore.Scope scope, String task, String run) {
    DurableTaskStore.require(run, 256);
    return store.write(db -> {
      var owner = store.status(db, scope, task);
      if (!owner.state().equals("ACTIVE") && !owner.state().equals("COMPLETE"))
        throw new IllegalStateException("TASK_NOT_ACTIVE");
      if (DurableTaskStore.number(db, "SELECT COUNT(*) FROM durable_tasks WHERE id=? AND current_run=?", task, run) != 0)
        return owner;
      // A previously retired Run may not reclaim authority by replaying an old resume.
      if (DurableTaskStore.number(db, "SELECT COUNT(*) FROM durable_attempts WHERE task=? AND run=?", task, run) != 0)
        throw new IllegalArgumentException("TASK_RUN_RETIRED");
      reconcile(db, task);
      DurableTaskStore.execute(db, "UPDATE durable_item_attempts SET state='REVOKED' WHERE state='ACTIVE' AND work IN (SELECT id FROM durable_work WHERE task=?)", task);
      DurableTaskStore.execute(db, "UPDATE durable_work SET state='PENDING',active_attempt=NULL,last_step='RECOVERED' WHERE task=? AND state='RUNNING' AND result_id IS NULL", task);
      DurableTaskStore.execute(db, "UPDATE durable_attempts SET state='RETIRED' WHERE task=? AND state='ACTIVE'", task);
      DurableTaskStore.execute(db, "INSERT INTO durable_attempts(task,run,started,state) VALUES(?,?,?,'ACTIVE')", task, run, System.currentTimeMillis());
      DurableTaskStore.execute(db, "UPDATE durable_tasks SET current_run=? WHERE id=?", run, task);
      if (TaskCompletion.verify(db, store.status(db, scope, task)).complete())
        DurableTaskStore.execute(db, "UPDATE durable_tasks SET state='COMPLETE' WHERE id=?", task);
      return store.status(db, scope, task);
    });
  }

  private static void reconcile(Connection db, String task) throws SQLException {
    String cursor = "";
    while (true) {
      var ids = new ArrayList<String>();
      try (var query = DurableTaskStore.statement(db, "SELECT r.work FROM durable_results r JOIN durable_work w ON w.id=r.work WHERE w.task=? AND r.work>? ORDER BY r.work LIMIT 100", task, cursor);
          var rows = query.executeQuery()) {
        while (rows.next()) ids.add(rows.getString(1));
      }
      for (var id : ids) {
        var item = item(db, id);
        try (var query = DurableTaskStore.statement(db, "SELECT body,sha256,task,snapshot,path,start,end,version,criterion FROM durable_results WHERE work=?", id);
            var rows = query.executeQuery()) {
          rows.next();
          var body = result(rows).body();
          if (!body.work().equals(item.id()) || !body.snapshot().equals(item.snapshot())
              || !body.path().equals(item.path()) || body.start() != item.start() || body.end() != item.end()
              || !body.version().equals(item.version()) || !body.criterion().equals(item.criterion())
              || !rows.getString(3).equals(item.task()) || !rows.getString(4).equals(item.snapshot())
              || !rows.getString(5).equals(item.path()) || rows.getInt(6) != item.start() || rows.getInt(7) != item.end()
              || !rows.getString(8).equals(item.version()) || !rows.getString(9).equals(item.criterion()))
            throw new IllegalArgumentException("RESULT_CORRUPT");
          source(db, item);
        }
        DurableTaskStore.execute(db, "UPDATE durable_work SET state='COMPLETE',result_id=?,last_step='COMPLETED' WHERE id=?", id, id);
        cursor = id;
      }
      if (ids.size() < 100) return;
    }
  }

  public Claim claim(DurableTaskStore.Scope scope, String task, String run) {
    return claim(scope, task, run, (item, source) -> item.end());
  }

  public Claim claim(DurableTaskStore.Scope scope, String task, String run,
      java.util.function.ToIntBiFunction<Item, String> split) {
    return store.write(db -> {
      requireRun(db, scope, task, run);
      Item pending = pending(db, task);
      if (pending == null) return null;
      String source = source(db, pending);
      int end = split.applyAsInt(pending, source);
      if (end < pending.start() || end > pending.end() || !boundary(source, end)
          || end == pending.start() && end < pending.end()) throw new IllegalArgumentException("WORK_SPLIT_INVALID");
      if (end < pending.end()) {
        DurableTaskStore.execute(db, "UPDATE durable_work SET end=? WHERE id=? AND state='PENDING'", end, pending.id());
        DurableTaskStore.execute(db, "INSERT INTO durable_work(id,task,snapshot,path,start,end,version,kind,criterion,state,required) VALUES(?,?,?,?,?,?,?,?,?,'PENDING',?)",
            UUID.randomUUID().toString(), task, pending.snapshot(), pending.path(), end, pending.end(), pending.version(), pending.kind(), pending.criterion(), pending.required());
      }
      String attempt = UUID.randomUUID().toString();
      if (DurableTaskStore.execute(db, "UPDATE durable_work SET state='RUNNING',active_attempt=?,last_step='CLAIMED' WHERE id=? AND state='PENDING'", attempt, pending.id()) != 1)
        throw new IllegalStateException("WORK_CLAIM_CONFLICT");
      DurableTaskStore.execute(db, "INSERT INTO durable_item_attempts(id,work,run,started,state) VALUES(?,?,?,?,'ACTIVE')", attempt, pending.id(), run, System.currentTimeMillis());
      return new Claim(item(db, pending.id()), run, attempt);
    });
  }

  public Claim current(DurableTaskStore.Scope scope, String work, String run, String attempt) {
    return store.read(db -> {
      var item = item(db, work);
      requireRun(db, scope, item.task(), run, true);
      var claim = new Claim(item, run, attempt);
      delivery(db, claim);
      return claim;
    });
  }

  public void delivered(DurableTaskStore.Scope scope, Claim claim, String contentHash) {
    store.write(db -> {
      store.status(db, scope, claim.item().task());
      String previous = delivery(db, claim);
      String text = source(db, claim.item());
      if (!contentHash.equals(hash(text.substring(claim.item().start(), claim.item().end()).getBytes(StandardCharsets.UTF_8))))
        throw new IllegalArgumentException("DELIVERY_RANGE_MISMATCH");
      if (previous.equals(contentHash)) return null;
      requireRun(db, scope, claim.item().task(), claim.run());
      if (!item(db, claim.item().id()).state().equals("RUNNING"))
        throw new IllegalArgumentException("STALE_CLAIM");
      DurableTaskStore.execute(db, "UPDATE durable_item_attempts SET delivered_hash=? WHERE id=? AND state='ACTIVE'", contentHash, claim.attempt());
      DurableTaskStore.execute(db, "UPDATE durable_work SET last_step='DELIVERED' WHERE id=?", claim.item().id());
      return null;
    });
  }

  public String deliveredHash(DurableTaskStore.Scope scope, Claim claim) {
    return store.read(db -> {
      store.status(db, scope, claim.item().task());
      return delivery(db, claim);
    });
  }

  static String delivery(Connection db, Claim claim) throws SQLException {
    try (var query = DurableTaskStore.statement(db, "SELECT a.run,a.delivered_hash FROM durable_item_attempts a JOIN durable_work w ON w.id=a.work AND w.active_attempt=a.id WHERE a.id=? AND w.id=?", claim.attempt(), claim.item().id());
        var rows = query.executeQuery()) {
      if (!rows.next()) throw new IllegalArgumentException("STALE_CLAIM");
      if (!rows.getString(1).equals(claim.run())) throw new IllegalArgumentException("TASK_RUN_NOT_BOUND");
      return rows.getString(2);
    }
  }

  void requireRun(Connection db, DurableTaskStore.Scope scope, String task, String run) throws SQLException {
    requireRun(db, scope, task, run, false);
  }

  private void requireRun(Connection db, DurableTaskStore.Scope scope, String task, String run, boolean replay) throws SQLException {
    var owner = store.status(db, scope, task);
    if (!owner.state().equals("ACTIVE") && !(replay && owner.state().equals("COMPLETE"))) throw new IllegalStateException("TASK_NOT_ACTIVE");
    if (!owner.enumeration().equals("SEALED")) throw new IllegalStateException("ENUMERATION_NOT_SEALED");
    try (var query = DurableTaskStore.statement(db, "SELECT 1 FROM durable_tasks t JOIN durable_attempts a ON a.task=t.id AND a.run=t.current_run WHERE t.id=? AND t.current_run=? AND a.state='ACTIVE'", task, run);
        var rows = query.executeQuery()) {
      if (!rows.next()) throw new IllegalArgumentException("TASK_RUN_NOT_BOUND");
    }
  }

  public Result commit(DurableTaskStore.Scope scope, Claim claim, String assessment, List<Finding> findings) {
    if (assessment == null || assessment.isBlank() || assessment.length() > 16384 || findings == null || findings.size() > 100)
      throw new IllegalArgumentException("REVIEW_RESULT_INVALID");
    return store.write(db -> {
      requireRun(db, scope, claim.item().task(), claim.run(), true);
      String delivered = delivery(db, claim);
      String source = source(db, claim.item());
      if (!delivered.equals(hash(source.substring(claim.item().start(), claim.item().end()).getBytes(StandardCharsets.UTF_8))))
        throw new IllegalArgumentException("SOURCE_NOT_DELIVERED");
      for (var finding : findings) {
        if (finding == null || finding.id() == null || finding.id().isBlank() || finding.id().length() > 256
            || finding.severity() == null || finding.severity().isBlank() || finding.severity().length() > 64
            || finding.evidence() == null || finding.evidence().isBlank() || finding.evidence().length() > 16384
            || finding.assessment() == null || finding.assessment().isBlank() || finding.assessment().length() > 16384
            || finding.start() < claim.item().start() || finding.end() > claim.item().end() || finding.end() <= finding.start()
            || !boundary(source, finding.start()) || !boundary(source, finding.end())
            || !source.substring(finding.start(), finding.end()).contains(finding.evidence()))
          throw new IllegalArgumentException("REVIEW_RESULT_INVALID");
      }
      if (findings.stream().map(Finding::id).distinct().count() != findings.size()) throw new IllegalArgumentException("REVIEW_RESULT_INVALID");
      var item = claim.item();
      var body = new ReviewResult(item.id(), item.snapshot(), item.path(), item.start(), item.end(), item.version(), item.criterion(), assessment, findings);
      String json = encode(body);
      byte[] encoded = json.getBytes(StandardCharsets.UTF_8);
      if (encoded.length > 256 * 1024) throw new IllegalArgumentException("RESULT_BYTE_LIMIT");
      String hash = hash(encoded);
      try (var query = DurableTaskStore.statement(db, "SELECT body,sha256 FROM durable_results WHERE work=?", item.id()); var rows = query.executeQuery()) {
        if (rows.next()) {
          if (!rows.getString(2).equals(hash)) throw new IllegalArgumentException("RESULT_CONFLICT");
          return result(rows);
        }
      }
      if (!item(db, item.id()).state().equals("RUNNING")) throw new IllegalArgumentException("STALE_CLAIM");
      DurableTaskStore.execute(db, "INSERT INTO durable_results(work,task,snapshot,path,start,end,version,criterion,attempt,body,sha256,created) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
          item.id(), item.task(), item.snapshot(), item.path(), item.start(), item.end(), item.version(), item.criterion(), claim.attempt(), json, hash, System.currentTimeMillis());
      for (var finding : findings)
        DurableTaskStore.execute(db, "INSERT INTO durable_findings(work,id,severity,start,end,evidence,assessment) VALUES(?,?,?,?,?,?,?)", item.id(), finding.id(), finding.severity(), finding.start(), finding.end(), finding.evidence(), finding.assessment());
      if (DurableTaskStore.execute(db, "UPDATE durable_work SET state='COMPLETE',result_id=?,last_step='COMPLETED' WHERE id=? AND state='RUNNING' AND active_attempt=?", item.id(), item.id(), claim.attempt()) != 1)
        throw new IllegalArgumentException("STALE_CLAIM");
      DurableTaskStore.execute(db, "UPDATE durable_item_attempts SET state='COMPLETE' WHERE id=?", claim.attempt());
      if (TaskCompletion.verify(db, store.status(db, scope, item.task())).complete())
        DurableTaskStore.execute(db, "UPDATE durable_tasks SET state='COMPLETE' WHERE id=? AND state='ACTIVE'", item.task());
      return new Result(body, hash);
    });
  }

  public DurableTaskStore.Page<Result> results(DurableTaskStore.Scope scope, String task, String after, int limit) {
    DurableTaskStore.pageLimit(limit);
    return store.read(db -> {
      store.status(db, scope, task);
      var entries = new ArrayList<Result>();
      try (var query = DurableTaskStore.statement(db, "SELECT body,sha256 FROM durable_results WHERE task=? AND work>? ORDER BY work LIMIT ?", task, Objects.toString(after, ""), limit + 1);
          var rows = query.executeQuery()) {
        while (rows.next()) entries.add(result(rows));
      }
      boolean more = entries.size() > limit;
      if (more) entries.removeLast();
      return new DurableTaskStore.Page<>(entries, more ? entries.getLast().body().work() : null);
    });
  }

  private static String encode(ReviewResult body) {
    try {
      return JSON.writeValueAsString(body);
    } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
      throw new IllegalArgumentException("REVIEW_RESULT_INVALID", invalid);
    }
  }

  private static Result result(ResultSet row) throws SQLException {
    String body = row.getString(1), hash = row.getString(2);
    if (!hash(body.getBytes(StandardCharsets.UTF_8)).equals(hash)) throw new IllegalArgumentException("RESULT_CORRUPT");
    try {
      return new Result(JSON.readValue(body, ReviewResult.class), hash);
    } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
      throw new IllegalArgumentException("RESULT_CORRUPT", invalid);
    }
  }

  static boolean boundary(String text, int position) {
    return position >= 0 && position <= text.length() && !(position > 0 && position < text.length()
        && Character.isHighSurrogate(text.charAt(position - 1)) && Character.isLowSurrogate(text.charAt(position)));
  }

  static Item pending(Connection db, String task) throws SQLException {
    try (var query = DurableTaskStore.statement(db, "SELECT * FROM durable_work WHERE task=? AND state='PENDING' AND required=1 ORDER BY path,start,id LIMIT 1", task);
        var rows = query.executeQuery()) {
      return rows.next() ? item(rows) : null;
    }
  }

  static Item item(Connection db, String id) throws SQLException {
    try (var query = DurableTaskStore.statement(db, "SELECT * FROM durable_work WHERE id=?", id); var rows = query.executeQuery()) {
      if (!rows.next()) throw new IllegalArgumentException("WORK_ITEM_NOT_FOUND");
      return item(rows);
    }
  }

  static Item item(ResultSet row) throws SQLException {
    return new Item(row.getString("id"), row.getString("task"), row.getString("snapshot"),
        row.getString("path"), row.getInt("start"), row.getInt("end"), row.getString("version"),
        row.getString("kind"), row.getString("criterion"), row.getString("state"), row.getBoolean("required"));
  }

  public String source(DurableTaskStore.Scope scope, Item expected) {
    return store.read(db -> {
      store.status(db, scope, expected.task());
      return source(db, expected);
    });
  }

  static String source(Connection db, Item expected) throws SQLException {
    try (var query = DurableTaskStore.statement(db,
        "SELECT f.content,f.text FROM durable_files f JOIN durable_work w ON w.snapshot=f.snapshot AND w.path=f.path WHERE w.id=? AND w.task=? AND w.snapshot=? AND w.path=? AND w.start=? AND w.end=? AND w.version=? AND f.version=w.version AND f.error='' AND f.reason=''",
        expected.id(), expected.task(), expected.snapshot(), expected.path(), expected.start(), expected.end(), expected.version());
        var row = query.executeQuery()) {
      if (!row.next()) throw new IllegalArgumentException("WORK_ITEM_CHANGED");
      byte[] content = row.getBytes(1);
      String text = row.getString(2);
      if (content == null || text == null || !hash(content).equals(expected.version())
          || !new String(content, StandardCharsets.UTF_8).equals(text))
        throw new IllegalArgumentException("SNAPSHOT_CORRUPT");
      return text;
    }
  }

  static String hash(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
