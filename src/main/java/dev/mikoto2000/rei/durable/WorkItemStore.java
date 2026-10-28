package dev.mikoto2000.rei.durable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.UUID;

/** Work-level claims and frozen sources share the existing serialized SQLite writer. */
public final class WorkItemStore {
  public record Item(String id, String task, String snapshot, String path, int start, int end,
      String version, String kind, String criterion, String state, boolean required) {}

  public record Claim(Item item, String run, String attempt) {}

  private final DurableTaskStore store;

  public WorkItemStore(DurableTaskStore store) {
    this.store = store;
  }

  public Claim claim(DurableTaskStore.Scope scope, String task, String run) {
    return store.write(db -> {
      requireRun(db, scope, task, run);
      Item pending = pending(db, task);
      if (pending == null) return null;
      String attempt = UUID.randomUUID().toString();
      if (DurableTaskStore.execute(db, "UPDATE durable_work SET state='RUNNING',active_attempt=?,last_step='CLAIMED' WHERE id=? AND state='PENDING'", attempt, pending.id()) != 1)
        throw new IllegalStateException("WORK_CLAIM_CONFLICT");
      DurableTaskStore.execute(db, "INSERT INTO durable_item_attempts(id,work,run,started,state) VALUES(?,?,?,?,'ACTIVE')", attempt, pending.id(), run, System.currentTimeMillis());
      return new Claim(item(db, pending.id()), run, attempt);
    });
  }

  void requireRun(Connection db, DurableTaskStore.Scope scope, String task, String run) throws SQLException {
    var owner = store.status(db, scope, task);
    if (!owner.state().equals("ACTIVE")) throw new IllegalStateException("TASK_NOT_ACTIVE");
    if (!owner.enumeration().equals("SEALED")) throw new IllegalStateException("ENUMERATION_NOT_SEALED");
    try (var query = DurableTaskStore.statement(db, "SELECT 1 FROM durable_tasks t JOIN durable_attempts a ON a.task=t.id AND a.run=t.current_run WHERE t.id=? AND t.current_run=? AND a.state='ACTIVE'", task, run);
        var rows = query.executeQuery()) {
      if (!rows.next()) throw new IllegalArgumentException("TASK_RUN_NOT_BOUND");
    }
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
