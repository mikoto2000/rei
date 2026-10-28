package dev.mikoto2000.rei.durable;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Durable work identity and frozen target inventory, independent of Run and Checkpoint lifetime. */
public final class DurableTaskStore {
  @FunctionalInterface
  public interface Work<T> {
    T run(Connection connection) throws Exception;
  }

  @FunctionalInterface
  public interface Access {
    Object run(boolean write, Work<?> work);
  }

  public record Scope(String project, String session, String root) {
    public Scope {
      require(project, 256);
      require(session, 512);
      require(root, 4096);
      root = Path.of(root).toAbsolutePath().normalize().toString();
    }
  }

  public record Task(
      String id, String snapshot, String goal, String state, String enumeration, String reason,
      long files, long excluded, long errors, long bytes, long unscannedDirectories, long revision) {}

  public record FileEntry(String path, String version, long bytes, String exclusionReason, String error) {}

  public record Page<T>(List<T> entries, String nextCursor) {
    public Page {
      entries = List.copyOf(entries);
    }
  }

  public record Verification(boolean complete, String reason) {}

  public record Source(String mode, String reference) {}

  record Directory(String path, String cursor) {}

  record Captured(String path, byte[] content, String text, String version, String reason, String error, boolean directory) {
    Captured {
      content = content == null ? null : content.clone();
    }

    @Override
    public byte[] content() {
      return content == null ? null : content.clone();
    }

    long size() {
      return content == null ? 0 : content.length;
    }
  }

  private final Access database;

  public DurableTaskStore(Access database) {
    this.database = Objects.requireNonNull(database);
  }

  public Task begin(Scope scope, String goal, String mode, String sourceRef, List<String> excludes,
      int maxFiles, long maxBytes) {
    if (!("WORKTREE".equals(mode) || "COMMIT".equals(mode)) || maxFiles < 1 || maxFiles > 200000 || maxBytes < 1
        || maxBytes > 16L * 1024 * 1024 * 1024 || excludes == null || excludes.size() > 64)
      throw new IllegalArgumentException("INVALID_SNAPSHOT_OPTIONS");
    if ("COMMIT".equals(mode) && (sourceRef == null || !sourceRef.matches("[0-9a-f]{40}|[0-9a-f]{64}")))
      throw new IllegalArgumentException("FULL_COMMIT_ID_REQUIRED");
    for (var exclude : excludes) {
      require(exclude, 1024);
      if (exclude.indexOf('\n') >= 0 || exclude.indexOf('\0') >= 0)
        throw new IllegalArgumentException("INVALID_EXCLUSION");
      java.nio.file.FileSystems.getDefault().getPathMatcher("glob:" + exclude);
    }
    String id = UUID.randomUUID().toString(), snapshot = UUID.randomUUID().toString();
    return write(db -> {
      execute(db, "INSERT INTO durable_tasks(id,project,session,root,goal,snapshot,state) VALUES(?,?,?,?,?,?,'ACTIVE')",
          id, scope.project(), scope.session(), scope.root(), goal, snapshot);
      execute(db, "INSERT INTO durable_snapshots(id,task,mode,source_ref,state,reason,excludes,max_files,max_bytes) VALUES(?,?,?,?,'SCANNING','',?,?,?)",
          snapshot, id, mode, Objects.toString(sourceRef, ""), String.join("\n", excludes), maxFiles, maxBytes);
      execute(db, "INSERT INTO durable_directories(task,path) VALUES(?,'')", id);
      return status(db, scope, id);
    });
  }

  public Task status(Scope scope, String id) {
    return read(db -> status(db, scope, id));
  }

  public Source source(Scope scope, String task) {
    return read(db -> {
      var owner = status(db, scope, task);
      return source(db, owner.snapshot());
    });
  }

  private Source source(Connection db, String snapshot) throws Exception {
    try (var query = statement(db, "SELECT mode,source_ref FROM durable_snapshots WHERE id=?", snapshot);
        var row = query.executeQuery()) {
      row.next();
      return new Source(row.getString(1), row.getString(2));
    }
  }

  private Task status(Connection db, Scope scope, String id) throws Exception {
    try (var query = statement(db,
        "SELECT t.*,s.state AS enumeration,s.reason,s.files,s.excluded,s.errors,s.bytes,(SELECT COUNT(*) FROM durable_directories d WHERE d.task=t.id AND d.state='PENDING') AS unscanned FROM durable_tasks t JOIN durable_snapshots s ON s.id=t.snapshot WHERE t.id=? AND t.project=? AND t.session=? AND t.root=?",
        id, scope.project(), scope.session(), scope.root()); var row = query.executeQuery()) {
      if (!row.next()) throw new IllegalArgumentException("TASK_ACCESS_DENIED");
      return new Task(row.getString("id"), row.getString("snapshot"), row.getString("goal"),
          row.getString("state"), row.getString("enumeration"), row.getString("reason"),
          row.getLong("files"), row.getLong("excluded"), row.getLong("errors"), row.getLong("bytes"),
          row.getLong("unscanned"), row.getLong("revision"));
    }
  }

  public Page<FileEntry> files(Scope scope, String task, String after, int limit) {
    pageLimit(limit);
    return read(db -> {
      var owner = status(db, scope, task);
      var entries = new ArrayList<FileEntry>();
      try (var query = statement(db,
          "SELECT path,version,bytes,reason,error FROM durable_files WHERE snapshot=? AND path>? ORDER BY path LIMIT ?",
          owner.snapshot(), Objects.toString(after, ""), limit + 1); var rows = query.executeQuery()) {
        while (rows.next()) entries.add(new FileEntry(rows.getString(1), rows.getString(2), rows.getLong(3), rows.getString(4), rows.getString(5)));
      }
      boolean more = entries.size() > limit;
      if (more) entries.removeLast();
      return new Page<>(entries, more ? entries.getLast().path() : null);
    });
  }

  public byte[] content(Scope scope, String task, String path) {
    return read(db -> {
      var owner = status(db, scope, task);
      try (var query = statement(db, "SELECT content FROM durable_files WHERE snapshot=? AND path=? AND error='' AND reason=''",
          owner.snapshot(), path); var row = query.executeQuery()) {
        if (!row.next()) throw new IllegalArgumentException("SNAPSHOT_CONTENT_UNAVAILABLE");
        return row.getBytes(1);
      }
    });
  }

  public Verification verify(Scope scope, String task) {
    return read(db -> {
      var owner = status(db, scope, task);
      if (!owner.state().equals("ACTIVE")) return new Verification(false, owner.state());
      if (!owner.enumeration().equals("SEALED") || owner.unscannedDirectories() != 0)
        return new Verification(false, "ENUMERATION_" + owner.enumeration());
      if (owner.errors() != 0) return new Verification(false, "ENUMERATION_ERRORS");
      if (owner.files() == 0) return new Verification(false, "EMPTY_TARGET_SET");
      long registered = number(db, "SELECT COUNT(DISTINCT path) FROM durable_work WHERE task=? AND required=1", task);
      if (registered != owner.files()) return new Verification(false, "WORK_REGISTRATION_INCOMPLETE");
      // Result validation is added with the executor. Inventory alone never completes a task.
      return new Verification(false, "WORK_PENDING");
    });
  }

  Directory nextDirectory(Scope scope, String task) {
    return read(db -> {
      var owner = status(db, scope, task);
      if (!owner.state().equals("ACTIVE") || !owner.enumeration().equals("SCANNING")) return null;
      try (var query = statement(db, "SELECT path,cursor FROM durable_directories WHERE task=? AND state='PENDING' ORDER BY path LIMIT 1", task);
          var rows = query.executeQuery()) {
        return rows.next() ? new Directory(rows.getString(1), rows.getString(2)) : null;
      }
    });
  }

  List<String> exclusions(Scope scope, String task) {
    return read(db -> {
      var owner = status(db, scope, task);
      try (var query = statement(db, "SELECT excludes FROM durable_snapshots WHERE id=?", owner.snapshot());
          var row = query.executeQuery()) {
        row.next();
        String value = row.getString(1);
        return value.isEmpty() ? List.of() : List.of(value.split("\n"));
      }
    });
  }

  void captured(Scope scope, String task, Directory directory, List<Captured> entries, boolean finished) {
    write(db -> {
      var owner = status(db, scope, task);
      if (!owner.state().equals("ACTIVE") || !owner.enumeration().equals("SCANNING"))
        throw new IllegalStateException("TASK_NOT_SCANNING");
      String cursor = entries.isEmpty() ? directory.cursor()
          : source(db, owner.snapshot()).mode().equals("COMMIT") ? entries.getLast().path() : name(entries.getLast().path());
      if (execute(db, "UPDATE durable_directories SET cursor=?,state=? WHERE task=? AND path=? AND cursor=? AND state='PENDING'",
          cursor, finished ? "DONE" : "PENDING", task, directory.path(), directory.cursor()) != 1)
        throw new IllegalStateException("SCAN_OWNERSHIP_CHANGED");
      long maxFiles, maxBytes;
      try (var query = statement(db, "SELECT max_files,max_bytes FROM durable_snapshots WHERE id=?", owner.snapshot()); var row = query.executeQuery()) {
        row.next();
        maxFiles = row.getLong(1);
        maxBytes = row.getLong(2);
      }
      long count = owner.files(), bytes = owner.bytes(), excluded = owner.excluded(), errors = owner.errors();
      for (var entry : entries) {
        if (entry.directory() && entry.reason().isEmpty() && entry.error().isEmpty()) {
          execute(db, "INSERT INTO durable_directories(task,path) VALUES(?,?) ON CONFLICT DO NOTHING", task, entry.path());
          continue;
        }
        long size = entry.size();
        if (entry.reason().isEmpty() && entry.error().isEmpty() && (count >= maxFiles || bytes + size > maxBytes)) {
          execute(db, "UPDATE durable_snapshots SET state='FAILED',reason=? WHERE id=?", count >= maxFiles ? "FILE_LIMIT" : "BYTE_LIMIT", owner.snapshot());
          // The unpersisted suffix remains explicit; no seal is possible after this failure.
          execute(db, "UPDATE durable_directories SET state='PENDING' WHERE task=? AND path=?", task, directory.path());
          break;
        }
        execute(db, "INSERT INTO durable_files(snapshot,path,version,content,text,bytes,reason,error) VALUES(?,?,?,?,?,?,?,?)",
            owner.snapshot(), entry.path(), entry.version(), entry.content(), entry.text(), size, entry.reason(), entry.error());
        if (!entry.error().isEmpty()) errors++;
        else if (!entry.reason().isEmpty()) excluded++;
        else {
          count++;
          bytes += size;
          execute(db, "INSERT INTO durable_work(id,task,snapshot,path,start,end,version,kind,criterion,state,required) VALUES(?,?,?,?,0,?,?,'LOCAL','correctness','PENDING',1)",
              UUID.randomUUID().toString(), task, owner.snapshot(), entry.path(), entry.text().length(), entry.version());
        }
      }
      execute(db, "UPDATE durable_snapshots SET files=?,excluded=?,errors=?,bytes=? WHERE id=?", count, excluded, errors, bytes, owner.snapshot());
      if (errors > 0) execute(db, "UPDATE durable_snapshots SET state='FAILED',reason='ACCESS_ERROR' WHERE id=?", owner.snapshot());
      execute(db, "UPDATE durable_snapshots SET state='SEALED' WHERE id=? AND state='SCANNING' AND NOT EXISTS(SELECT 1 FROM durable_directories WHERE task=? AND state='PENDING')", owner.snapshot(), task);
      execute(db, "UPDATE durable_tasks SET revision=revision+1 WHERE id=?", task);
      return null;
    });
  }

  void scanFailed(Scope scope, String task, String path, String error) {
    write(db -> {
      var owner = status(db, scope, task);
      if (!owner.enumeration().equals("SCANNING")) return null;
      execute(db, "INSERT INTO durable_files(snapshot,path,version,bytes,reason,error) VALUES(?,?,'',0,'',?) ON CONFLICT DO NOTHING", owner.snapshot(), path.isEmpty() ? "." : path, error);
      execute(db, "UPDATE durable_snapshots SET state='FAILED',reason=?,errors=errors+1 WHERE id=?", error, owner.snapshot());
      return null;
    });
  }

  static String name(String path) {
    return path.substring(path.lastIndexOf('/') + 1);
  }

  static void pageLimit(int limit) {
    if (limit < 1 || limit > 1000) throw new IllegalArgumentException("PAGE_LIMIT_1_TO_1000");
  }

  static void require(String value, int limit) {
    if (value == null || value.isBlank() || value.length() > limit)
      throw new IllegalArgumentException("INVALID_TASK_ARGUMENT");
  }

  static PreparedStatement statement(Connection db, String sql, Object... args) throws Exception {
    var query = db.prepareStatement(sql);
    try {
      for (int i = 0; i < args.length; i++) {
        if (args[i] instanceof byte[] bytes) query.setBytes(i + 1, bytes);
        else query.setObject(i + 1, args[i]);
      }
      return query;
    } catch (Exception error) {
      query.close();
      throw error;
    }
  }

  static int execute(Connection db, String sql, Object... args) throws Exception {
    try (var query = statement(db, sql, args)) {
      return query.executeUpdate();
    }
  }

  static long number(Connection db, String sql, Object... args) throws Exception {
    try (var query = statement(db, sql, args); ResultSet row = query.executeQuery()) {
      row.next();
      return row.getLong(1);
    }
  }

  @SuppressWarnings("unchecked")
  <T> T read(Work<T> work) {
    try {
      return (T) database.run(false, work);
    } catch (IllegalStateException error) {
      if (error.getCause() instanceof IllegalArgumentException invalid) throw invalid;
      throw error;
    }
  }

  @SuppressWarnings("unchecked")
  <T> T write(Work<T> work) {
    return (T) database.run(true, work);
  }
}
