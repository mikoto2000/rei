package dev.mikoto2000.rei.conversation;

import dev.mikoto2000.rei.application.run.IdempotencyConflictException;
import dev.mikoto2000.rei.application.run.IdempotencyExpiredException;
import dev.mikoto2000.rei.application.run.ResourceNotFoundException;
import dev.mikoto2000.rei.application.run.RunFailure;
import dev.mikoto2000.rei.application.run.RunSnapshot;
import dev.mikoto2000.rei.application.run.RunStatus;
import dev.mikoto2000.rei.application.run.SessionBusyException;
import dev.mikoto2000.rei.application.run.SessionConflictException;
import dev.mikoto2000.rei.application.session.SessionMetadata;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.ResponseStyle;
import dev.mikoto2000.rei.storage.StorageDatabase;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 会話受付の記録を保持し、後続の処理で参照できるようにする。
 *
 * <p>Storage transaction commits Session, canonical Run, lease and receipt before dispatch.
 */
public final class ConversationAdmissionStore implements AutoCloseable {
  /** 受付記録保持期間。 */
  public static final Duration RECEIPT_RETENTION = Duration.ofDays(7);

  /** 許容件数。 */
  public static final int CAPACITY = 100000;

  /** 開始。 */
  private static final Set<String> OPEN = java.util.concurrent.ConcurrentHashMap.newKeySet();

  /** データベース。 */
  private final StorageDatabase database;

  /** 期限や記録日時の判定に使う時刻の取得元。 */
  private final Clock clock;

  /** 起動単位の識別子。 */
  private final String instance = UUID.randomUUID().toString();

  /** 終了済み。 */
  private boolean closed;

  /** 実行の所有者が cleanup 後に終端を確定する Run。イベントの二重適用を防ぐ。 */
  private final Set<String> deferredTerminals = java.util.concurrent.ConcurrentHashMap.newKeySet();

  /** RunService の実行区間では、終端の適用を正規 RunRegistry に委ねる。 */
  public void deferTerminalEvents(String runId, boolean deferred) {
    if (deferred) deferredTerminals.add(runId);
    else deferredTerminals.remove(runId);
  }

  /** 購読。 */
  private dev.mikoto2000.rei.event.AgentEventBus.Subscription subscription;

  /** 状況を判断する根拠を得るため、会話受付保存先を観測する。 */
  public void observe(dev.mikoto2000.rei.event.AgentEventBus events) {
    subscription =
        events.subscribe(
            event -> {
              if (event.runId() == null) return;
              RunStatus next =
                  switch (event.type()) {
                    case AGENT_RUN_STARTED -> RunStatus.RUNNING;
                    case AGENT_RUN_COMPLETED -> RunStatus.COMPLETED;
                    case AGENT_RUN_FAILED -> RunStatus.FAILED;
                    case AGENT_RUN_CANCELLED -> RunStatus.CANCELLED;
                    default -> null;
                  };
              if (next != null && !(next.isTerminal() && deferredTerminals.contains(event.runId())))
                transition(event.runId(), next);
            });
  }

  /**
   * 会話履歴と受付記録の管理で受け渡す受付記録を表す。
   *
   * @param context 文脈
   * @param replay 再送
   */
  public record Receipt(AgentRunContext context, boolean replay) {}

  /**
   * 会話履歴と受付記録の管理で受け渡す文脈を表す。
   *
   * @param runId 実行の識別子
   * @param sessionId 会話の識別子
   * @param root 作業ルート
   * @param projectId 所属プロジェクトの識別子
   * @param source 取得元
   * @param mode 動作モード
   * @param voiceInput 音声入力からの依頼かどうか
   * @param responseStyle 会話の応答形式
   */
  private record Context(
      String runId,
      String sessionId,
      String root,
      String projectId,
      AgentRunContext.RequestSource source,
      AgentRunContext.Mode mode,
      boolean voiceInput,
      ResponseStyle responseStyle) {
    /** 会話履歴と受付記録の管理で受け渡す情報を、指定された内容から作成する。 */
    static Context of(AgentRunContext c) {
      return new Context(
          c.runId(),
          c.conversationId(),
          c.projectRoot().toString(),
          c.projectId(),
          c.requestSource(),
          c.mode(),
          c.voiceInput(),
          c.responseStyle());
    }

    /** 後続の処理で使う値を返す。 */
    AgentRunContext value() {
      return new AgentRunContext(
          runId, sessionId, Path.of(root), projectId, source, mode, voiceInput, responseStyle);
    }
  }

  /** 指定された値や利用先を使って、ConversationAdmissionStoreを初期化する。 */
  public ConversationAdmissionStore(Path root, Clock clock) {
    this.database = new StorageDatabase(root);
    this.clock = clock;
    OPEN.add(instance);
    try {
      recover();
    } catch (RuntimeException error) {
      OPEN.remove(instance);
      throw error;
    }
  }

  /** 処理を継続できる状態に戻すため、会話受付保存先を復旧する。 */
  private void recover() {
    database.transaction(
        db -> {
          var lost = new ArrayList<String>();
          try (var query = db.createStatement();
              var rows =
                  query.executeQuery(
                      "SELECT run_id,owner_instance,owner_pid,owner_start FROM conversation_admissions WHERE active=1 OR status IN ('QUEUED','RUNNING','WAITING','WAITING_APPROVAL')")) {
            while (rows.next()) {
              long pid = rows.getLong(3);
              String start = rows.getString(4), owner = rows.getString(2);
              boolean alive =
                  start != null
                      && ProcessHandle.of(pid)
                          .filter(ProcessHandle::isAlive)
                          .flatMap(p -> p.info().startInstant())
                          .map(t -> t.toString().equals(start))
                          .orElse(false);
              if (!alive || pid == ProcessHandle.current().pid() && !OPEN.contains(owner))
                lost.add(rows.getString(1));
            }
          }
          for (String id : lost) unknown(db, id);
          return null;
        });
  }

  /** 内容の同一性を照合するためのハッシュ値を求める。 */
  public static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              java.security.MessageDigest.getInstance("SHA-256")
                  .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  /** 会話履歴と受付記録の管理で用いるキーハッシュ値を返す。 */
  private static String keyHash(String key) {
    if (key == null || !key.matches("[A-Za-z0-9._:-]{1,128}"))
      throw new IllegalArgumentException("Invalid Idempotency-Key");
    return hash(key);
  }

  /**
   * 会話履歴と受付記録の管理で受け渡す保存済み受付記録を表す。
   *
   * @param fingerprint 要求内容の照合値
   * @param runId 実行の識別子
   * @param expires 有効期限
   */
  private record SavedReceipt(String fingerprint, String runId, Instant expires) {}

  /** 会話履歴と受付記録の管理で用いる受付記録を返す。 */
  private SavedReceipt receipt(Connection db, String hash) throws SQLException {
    try (var query =
        db.prepareStatement(
            "SELECT fingerprint,run_id,expires FROM chat_receipts WHERE key_hash=?")) {
      query.setString(1, hash);
      try (var rows = query.executeQuery()) {
        return rows.next()
            ? new SavedReceipt(
                rows.getString(1), rows.getString(2), Instant.parse(rows.getString(3)))
            : null;
      }
    }
  }

  /** 会話履歴と受付記録の管理に関するunexpiredを処理する。 */
  private void unexpired(SavedReceipt value) {
    if (!clock.instant().isBefore(value.expires())) throw new IdempotencyExpiredException();
  }

  /** 会話履歴と受付記録の管理で用いる照会を返す。 */
  public Optional<RunSnapshot> lookup(String key) {
    String hash = keyHash(key);
    var value = database.read(db -> receipt(db, hash));
    if (value == null) return Optional.empty();
    unexpired(value);
    return get(value.runId());
  }

  /** 指定された対象の情報を、呼び出し元が参照できる形で返す。 */
  public Optional<RunSnapshot> get(String runId) {
    return database.read(db -> snapshot(db, runId));
  }

  /** 現在の状態を、後から参照できる記録として取り出す。 */
  private Optional<RunSnapshot> snapshot(Connection db, String id) throws Exception {
    try (var query =
        db.prepareStatement(
            "SELECT context,status,started,completed FROM conversation_admissions WHERE run_id=?")) {
      query.setString(1, id);
      try (var rows = query.executeQuery()) {
        if (!rows.next()) return Optional.empty();
        String json = rows.getString(1);
        // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
        if (json.length() > 20000)
          throw new IllegalStateException("Admission context capacity exceeded");
        var context = StorageDatabase.JSON.readValue(json, Context.class).value();
        var status = RunStatus.valueOf(rows.getString(2));
        if (!context.runId().equals(id))
          throw new IllegalStateException("Admission Run identity mismatch");
        return Optional.of(
            new RunSnapshot(
                context,
                status,
                instant(rows.getString(3)),
                instant(rows.getString(4)),
                status == RunStatus.UNKNOWN
                    ? new RunFailure("OwnerLost", "Inspect checkpoint before explicit resume")
                    : status == RunStatus.FAILED
                        ? new RunFailure("ExecutionFailure", "Agent run failed")
                        : null));
      }
    }
  }

  /** 会話履歴と受付記録の管理で用いる日時を返す。 */
  private static Instant instant(String value) {
    return value == null ? null : Instant.parse(value);
  }

  /** 要求を後続の処理へ引き渡すため、会話受付保存先を受け付ける。 */
  public Receipt accept(
      SessionMetadata metadata, AgentRunContext context, String key, String fingerprint) {
    return accept(metadata, context, key, fingerprint, null);
  }

  /** 要求を後続の処理へ引き渡すため、会話受付保存先を受け付ける。 */
  public Receipt accept(
      SessionMetadata metadata,
      AgentRunContext context,
      String key,
      String fingerprint,
      dev.mikoto2000.rei.mcp.McpAdmission mcp) {
    String hash = key == null ? null : keyHash(key);
    if (hash != null && (fingerprint == null || !fingerprint.matches("[a-f0-9]{64}")))
      throw new IllegalArgumentException("Invalid request fingerprint");
    if (!metadata.sessionId().equals(context.conversationId())
        || !metadata.projectId().equals(context.projectId()))
      throw new IllegalArgumentException("Admission ownership mismatch");
    synchronized (database.writer) {
      if (closed) throw new IllegalStateException("Admission store closed");
      return database.transaction(
          db -> {
            if (mcp != null) {
              if (context.requestSource() != AgentRunContext.RequestSource.MCP
                  || context.mode() != AgentRunContext.Mode.READ_ONLY)
                throw new IllegalArgumentException("MCP requires READ_ONLY");
              try (var query =
                  db.prepareStatement(
                      "SELECT fingerprint,run_id,expires_at FROM mcp_requests WHERE principal='local-mcp' AND request_hash=?")) {
                query.setString(1, mcp.requestHash());
                try (var rows = query.executeQuery()) {
                  if (rows.next()) {
                    if (!clock.instant().isBefore(Instant.parse(rows.getString(3))))
                      throw new IdempotencyExpiredException();
                    if (!mcp.fingerprint().equals(rows.getString(1)))
                      throw new IdempotencyConflictException();
                    return new Receipt(
                        snapshot(db, rows.getString(2)).orElseThrow().context(), true);
                  }
                }
              }
              try (var query = db.prepareStatement("SELECT 1 FROM sessions WHERE session_id=?")) {
                query.setString(1, metadata.sessionId());
                try (var rows = query.executeQuery()) {
                  if (rows.next())
                    try (var owner =
                        db.prepareStatement(
                            "SELECT 1 FROM mcp_sessions WHERE session_id=? AND principal='local-mcp' AND project_id=?")) {
                      owner.setString(1, metadata.sessionId());
                      owner.setString(2, metadata.projectId());
                      try (var owned = owner.executeQuery()) {
                        if (!owned.next()) throw new ResourceNotFoundException("Session");
                      }
                    }
                }
              }
            }
            if (hash != null) {
              var old = receipt(db, hash);
              if (old != null) {
                unexpired(old);
                if (!old.fingerprint().equals(fingerprint))
                  throw new IdempotencyConflictException();
                return new Receipt(snapshot(db, old.runId()).orElseThrow().context(), true);
              }
              capacity(db, "chat_receipts");
            }
            reserve(db, context);
            long revision = 1;
            SessionMetadata next = metadata;
            try (var query =
                db.prepareStatement("SELECT record,revision FROM sessions WHERE session_id=?")) {
              query.setString(1, metadata.sessionId());
              try (var rows = query.executeQuery()) {
                if (rows.next()) {
                  var old =
                      StorageDatabase.JSON.readValue(rows.getString(1), SessionMetadata.class);
                  // 別のプロジェクトの情報を、この作業の対象として扱わせない。
                  if (!old.projectId().equals(metadata.projectId())
                      || !old.createdAt().equals(metadata.createdAt())
                      || !old.title().equals(metadata.title()))
                    throw new IllegalArgumentException("Immutable Session metadata");
                  revision = Math.addExact(rows.getLong(2), 1);
                  next = metadata.touched(old.updatedAt());
                }
              }
            }
            SqliteSessionRepository.put(db, next, revision, null);
            if (hash != null)
              try (var write = db.prepareStatement("INSERT INTO chat_receipts VALUES(?,?,?,?)")) {
                write.setString(1, hash);
                write.setString(2, fingerprint);
                write.setString(3, context.runId());
                write.setString(4, clock.instant().plus(RECEIPT_RETENTION).toString());
                write.executeUpdate();
              }
            if (mcp != null) {
              try (var write =
                  db.prepareStatement(
                      "INSERT OR IGNORE INTO mcp_sessions VALUES(?,'local-mcp',?)")) {
                write.setString(1, metadata.sessionId());
                write.setString(2, metadata.projectId());
                write.executeUpdate();
              }
              try (var write =
                  db.prepareStatement(
                      "INSERT INTO mcp_requests(principal,request_hash,fingerprint,project_id,session_id,run_id,created_at,expires_at,total_criteria,criteria_json) VALUES('local-mcp',?,?,?,?,?,?,?,?,?)")) {
                write.setString(1, mcp.requestHash());
                write.setString(2, mcp.fingerprint());
                write.setString(3, metadata.projectId());
                write.setString(4, metadata.sessionId());
                write.setString(5, context.runId());
                write.setString(6, clock.instant().toString());
                write.setString(7, clock.instant().plus(mcp.retention()).toString());
                write.setInt(8, mcp.totalCriteria());
                write.setString(9, StorageDatabase.JSON.writeValueAsString(mcp.criteria()));
                write.executeUpdate();
              }
            }
            return new Receipt(context, false);
          });
    }
  }

  /** 会話履歴と受付記録の管理に関する許容件数を処理する。 */
  private static void capacity(Connection db, String table) throws SQLException {
    // Table names are private constants, never caller input. Expired key tombstones are not reused.
    try (var query = db.createStatement();
        var rows = query.executeQuery("SELECT count(*) FROM " + table)) {
      if (rows.next() && rows.getLong(1) >= CAPACITY)
        throw new java.util.concurrent.RejectedExecutionException(
            "Durable conversation admission capacity reached");
    }
  }

  /**
   * 競合する処理と利用範囲を調整するため、既知会話を確保する。
   *
   * <p>Checkpoint/Scheduler entry points also reserve known human Sessions before enqueue.
   */
  public void reserveKnownSession(AgentRunContext context) {
    database.transaction(
        db -> {
          try (var query =
              db.prepareStatement("SELECT project_id FROM sessions WHERE session_id=?")) {
            query.setString(1, context.conversationId());
            try (var rows = query.executeQuery()) {
              if (rows.next()) {
                if (!rows.getString(1).equals(context.projectId()))
                  throw new SessionConflictException();
                reserve(db, context);
              }
            }
          }
          return null;
        });
  }

  /** 競合する処理と利用範囲を調整するため、会話受付保存先を確保する。 */
  private void reserve(Connection db, AgentRunContext context) throws Exception {
    var existing = snapshot(db, context.runId());
    if (existing.isPresent()) {
      if (!existing.get().context().equals(context))
        throw new IllegalArgumentException("Admission identity mismatch");
      return;
    }
    boolean deferred = false;
    try (var query =
        db.prepareStatement(
            "SELECT run_id,status FROM conversation_admissions WHERE project_id=? AND session_id=? AND (active=1 OR status IN ('QUEUED','WAITING','WAITING_APPROVAL'))")) {
      query.setString(1, context.projectId());
      query.setString(2, context.conversationId());
      try (var rows = query.executeQuery()) {
        if (rows.next()) {
          if (context.requestSource() == AgentRunContext.RequestSource.SHELL
              && context.mode() == AgentRunContext.Mode.EXCLUSIVE
              && !Set.of("WAITING", "WAITING_APPROVAL").contains(rows.getString(2)))
            deferred = true;
          else
            throw new SessionBusyException(
                context.projectId(),
                context.conversationId(),
                rows.getString(1),
                rows.getString(2));
        }
      }
    }
    capacity(db, "conversation_admissions");
    String document = StorageDatabase.JSON.writeValueAsString(Context.of(context));
    // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
    if (document.length() > 20000)
      throw new IllegalArgumentException("Admission context capacity exceeded");
    try (var write =
        db.prepareStatement(
            "INSERT INTO conversation_admissions(run_id,project_id,session_id,context,status,active,owner_instance,owner_pid,owner_start,accepted) VALUES(?,?,?,?,'QUEUED',?,?,?,?,?)")) {
      write.setString(1, context.runId());
      write.setString(2, context.projectId());
      write.setString(3, context.conversationId());
      write.setString(4, document);
      write.setInt(5, deferred ? 0 : 1);
      write.setString(6, instance);
      write.setLong(7, ProcessHandle.current().pid());
      write.setString(
          8, ProcessHandle.current().info().startInstant().map(Instant::toString).orElse(null));
      write.setString(9, clock.instant().toString());
      write.executeUpdate();
    }
  }

  /**
   * 要求された処理を利用できるよう、実行を開始する。
   *
   * <p>Shell's existing exclusive queue remains pending until the preceding runner has cleaned up.
   */
  public void startExecution(AgentRunContext context) {
    database.transaction(
        db -> {
          var saved = snapshot(db, context.runId());
          if (saved.isEmpty()) return null;
          // 終了済みの実行について、現在の状態に応じた扱いを選ぶ。
          if (!saved.get().context().equals(context) || saved.get().status().isTerminal())
            throw new IllegalStateException("Run cannot start");
          try (var query =
              db.prepareStatement(
                  "SELECT run_id,status FROM conversation_admissions WHERE project_id=? AND session_id=? AND (active=1 OR status IN ('WAITING','WAITING_APPROVAL')) AND run_id<>?")) {
            query.setString(1, context.projectId());
            query.setString(2, context.conversationId());
            query.setString(3, context.runId());
            try (var rows = query.executeQuery()) {
              if (rows.next())
                throw new SessionBusyException(
                    context.projectId(),
                    context.conversationId(),
                    rows.getString(1),
                    rows.getString(2));
            }
          }
          try (var write =
              db.prepareStatement(
                  "UPDATE conversation_admissions SET active=1 WHERE run_id=? AND owner_instance=?")) {
            write.setString(1, context.runId());
            write.setString(2, instance);
            if (write.executeUpdate() != 1) throw new IllegalStateException("Run owner mismatch");
          }
          return null;
        });
  }

  /** 会話履歴と受付記録の管理に関するMCP受付失敗を処理する。 */
  public void mcpAdmissionFailed(String id, RuntimeException failure) {
    String code =
        failure instanceof java.util.concurrent.RejectedExecutionException
            ? "CAPACITY_EXCEEDED"
            : failure instanceof SessionBusyException ? "SESSION_BUSY" : "INTERNAL_ERROR";
    database.transaction(
        db -> {
          try (var q =
              db.prepareStatement(
                  "UPDATE mcp_requests SET admission_error=? WHERE run_id=? AND principal='local-mcp'")) {
            q.setString(1, code);
            q.setString(2, id);
            q.executeUpdate();
            return null;
          }
        });
  }

  /** 会話履歴と受付記録の管理に関する状態の遷移を処理する。 */
  public void transition(String id, RunStatus next) {
    database.transaction(
        db -> {
          var old = snapshot(db, id);
          // 終了済みの実行について、現在の状態に応じた扱いを選ぶ。
          if (old.isEmpty() || old.get().status().isTerminal() || next == RunStatus.QUEUED)
            return null;
          try (var write =
              db.prepareStatement(
                  "UPDATE conversation_admissions SET status=?,started=COALESCE(started,?),completed=? WHERE run_id=? AND owner_instance=?")) {
            write.setString(1, next.name());
            write.setString(2, next == RunStatus.RUNNING ? clock.instant().toString() : null);
            write.setString(3, next.isTerminal() ? clock.instant().toString() : null);
            write.setString(4, id);
            write.setString(5, instance);
            if (write.executeUpdate() != 1) return null;
          }
          try (var update =
              db.prepareStatement("UPDATE mcp_requests SET state_updated_at=? WHERE run_id=?")) {
            update.setString(1, clock.instant().toString());
            update.setString(2, id);
            update.executeUpdate();
          }
          return null;
        });
  }

  /**
   * 対象を処理する担当を決めるため、待機中の利用権を確保する。
   *
   * <p>Adopt only a durable external wait whose previous admission owner has exited.
   */
  public boolean claimWaiting(AgentRunContext context) {
    return database.transaction(
        db -> {
          var saved = snapshot(db, context.runId());
          if (saved.isEmpty()) return true;
          if (!saved.get().status().isWaiting() || !saved.get().context().equals(context))
            return false;
          try (var query =
              db.prepareStatement(
                  "SELECT owner_instance,owner_pid,owner_start FROM conversation_admissions WHERE run_id=?")) {
            query.setString(1, context.runId());
            try (var rows = query.executeQuery()) {
              if (!rows.next()) return false;
              if (instance.equals(rows.getString(1))) return true;
              long pid = rows.getLong(2);
              String start = rows.getString(3);
              boolean alive =
                  start != null
                      && ProcessHandle.of(pid)
                          .filter(ProcessHandle::isAlive)
                          .flatMap(p -> p.info().startInstant())
                          .map(t -> t.toString().equals(start))
                          .orElse(false);
              if (alive
                  && (pid != ProcessHandle.current().pid() || OPEN.contains(rows.getString(1))))
                return false;
            }
          }
          try (var write =
              db.prepareStatement(
                  "UPDATE conversation_admissions SET owner_instance=?,owner_pid=?,owner_start=? WHERE run_id=?")) {
            write.setString(1, instance);
            write.setLong(2, ProcessHandle.current().pid());
            write.setString(
                3,
                ProcessHandle.current().info().startInstant().map(Instant::toString).orElse(null));
            write.setString(4, context.runId());
            write.executeUpdate();
          }
          return true;
        });
  }

  /** 後続の処理が利用できるよう、会話受付保存先を解放する。 */
  public void release(String id) {
    database.transaction(
        db -> {
          try (var query =
              db.prepareStatement(
                  "SELECT owner_instance FROM conversation_admissions WHERE run_id=?")) {
            query.setString(1, id);
            try (var rows = query.executeQuery()) {
              if (!rows.next() || !instance.equals(rows.getString(1))) return null;
            }
          }
          var old = snapshot(db, id);
          if (old.isEmpty()) return null;
          // 終了済みの実行について、現在の状態に応じた扱いを選ぶ。
          if (!old.get().status().isTerminal() && !old.get().status().isWaiting()) unknown(db, id);
          else
            try (var write =
                db.prepareStatement(
                    "UPDATE conversation_admissions SET active=0 WHERE run_id=? AND owner_instance=?")) {
              write.setString(1, id);
              write.setString(2, instance);
              write.executeUpdate();
            }
          return null;
        });
  }

  /** 確定できない結果を、判定不能の情報として表す。 */
  private void unknown(Connection db, String id) throws SQLException {
    try (var write =
        db.prepareStatement(
            "UPDATE conversation_admissions SET completed=CASE WHEN status IN ('WAITING','WAITING_APPROVAL') THEN NULL ELSE COALESCE(completed,?) END,owner_pid=CASE WHEN status IN ('WAITING','WAITING_APPROVAL') THEN 0 ELSE owner_pid END,owner_start=CASE WHEN status IN ('WAITING','WAITING_APPROVAL') THEN NULL ELSE owner_start END,status=CASE WHEN status IN ('QUEUED','RUNNING') THEN 'UNKNOWN' ELSE status END,active=0 WHERE run_id=?")) {
      write.setString(1, clock.instant().toString());
      write.setString(2, id);
      write.executeUpdate();
    }
    try (var update =
        db.prepareStatement("UPDATE mcp_requests SET state_updated_at=? WHERE run_id=?")) {
      update.setString(1, clock.instant().toString());
      update.setString(2, id);
      update.executeUpdate();
    }
  }

  /** 会話履歴と受付記録の管理で利用した資源や登録を解放し、利用を終了する。 */
  @Override
  public void close() {
    synchronized (database.writer) {
      if (closed) return;
      closed = true;
      if (subscription != null) subscription.unsubscribe();
      try {
        database.transaction(
            db -> {
              var owned = new ArrayList<String>();
              try (var query =
                  db.prepareStatement(
                      "SELECT run_id FROM conversation_admissions WHERE owner_instance=? AND (active=1 OR status IN ('QUEUED','RUNNING','WAITING','WAITING_APPROVAL'))")) {
                query.setString(1, instance);
                try (var rows = query.executeQuery()) {
                  while (rows.next()) owned.add(rows.getString(1));
                }
              }
              for (String id : owned) unknown(db, id);
              return null;
            });
      } finally {
        OPEN.remove(instance);
        deferredTerminals.clear();
      }
    }
  }
}
