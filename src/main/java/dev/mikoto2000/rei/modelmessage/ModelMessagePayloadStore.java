package dev.mikoto2000.rei.modelmessage;

import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.ai.chat.messages.Message;

/** Durable protocol messages; callers publish references before making them visible. */
@org.springframework.stereotype.Component
@org.springframework.context.annotation.Lazy
public final class ModelMessagePayloadStore {
  public static final String EXCHANGE_CONTEXT_KEY = "rei.modelMessageExchange";
  public static final String ORIGIN_CONTEXT_KEY = "rei.modelMessageOrigin";

  /** Provider-resolved compatibility values; no connection details or credentials. */
  public record Origin(String owner, String endpoint) {
    public Origin {
      identifier(owner);
      identifier(endpoint);
    }

    public void requireMatch(Message message) {
      if (!owner.equals(message.getMetadata().get("rei.llm.replay-origin-owner"))
          || !endpoint.equals(message.getMetadata().get("rei.llm.replay-origin-endpoint")))
        throw new IllegalStateException("Model message origin mismatch");
    }
  }

  public static final String HISTORY_ID = "rei.modelMessage.id";
  public static final String HISTORY_HASH = "rei.modelMessage.sha256";
  public static final String HISTORY_OWNER = "rei.modelMessage.historyOwner";

  public java.util.Map<String, String> historyReference(Scope scope, Ref ref, String owner) {
    publish(scope, ref, ReferenceKind.HISTORY, owner);
    return java.util.Map.of(HISTORY_ID, ref.id(), HISTORY_HASH, ref.sha256(), HISTORY_OWNER, owner);
  }

  public static boolean hasHistoryReference(java.util.Map<String, String> metadata) {
    return metadata != null
        && (metadata.containsKey(HISTORY_ID)
            || metadata.containsKey(HISTORY_HASH)
            || metadata.containsKey(HISTORY_OWNER));
  }

  public java.util.Optional<Message> restoreHistory(
      Scope scope, java.util.Map<String, String> metadata) {
    if (!hasHistoryReference(metadata)) return java.util.Optional.empty();
    validate(scope);
    String owner = metadata.get(HISTORY_OWNER);
    identifier(owner);
    var ref = new Ref(metadata.get(HISTORY_ID), metadata.get(HISTORY_HASH));
    return transaction(db -> {
      byte[] bytes = read(db, scope, ref);
      if (scalar(
              db,
              "SELECT COUNT(*) FROM model_message_references WHERE payload_id=? AND kind='HISTORY' AND owner=?",
              ref.id(),
              owner)
          != 1) throw rejected();
      return java.util.Optional.of(codec.decode(bytes));
    });
  }

  /**
   * One existing tool-loop iteration, with its immutable persistence identity supplied by the
   * caller.
   */
  public record Exchange(ModelMessagePayloadStore store, Scope scope, String run, String key) {
    public String ownerKey() {
      validate(scope);
      identifier(run);
      return digest(
          String.join("\0", scope.project(), scope.root(), scope.conversation(), run)
              .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public void persist(Message message) {
      reference(message);
    }

    public Ref reference(Message message) {
      var ref = store.save(scope, run, key + "/" + message.getMessageType().name(), message);
      store.publish(scope, ref, ReferenceKind.RUN, run);
      return ref;
    }
  }

  /** Validate the accepted owner before any model request or later payload save. */
  public Exchange newExchange(dev.mikoto2000.rei.tooldelivery.ReadingRun owner) {
    var scope = Scope.forOwner(owner);
    validate(scope);
    identifier(owner.runId());
    return new Exchange(this, scope, owner.runId(), UUID.randomUUID().toString());
  }

  public record Scope(String project, String root, String conversation) {
    public static Scope forOwner(dev.mikoto2000.rei.tooldelivery.ReadingRun owner) {
      if (owner == null || owner.projectRoot() == null) throw rejected();
      return new Scope(
          owner.projectId() == null ? "default" : "project:" + owner.projectId(),
          owner.projectRoot().toAbsolutePath().normalize().toString(),
          owner.conversationId());
    }
  }

  public record Ref(String id, String sha256) {}

  public record Limits(long runBytes, long totalBytes, java.time.Duration stagingLifetime) {}

  public record Usage(
      long payloadBytes, long runCumulativeBytes, long payloadCount, long referenceCount) {}

  public enum ReferenceKind {
    HISTORY,
    RUN,
    APPROVAL,
    CHECKPOINT
  }

  public void publish(Scope scope, Ref ref, ReferenceKind kind, String owner) {
    validate(scope);
    identifier(owner);
    if (kind == null) throw rejected();
    transaction(db -> {
      read(db, scope, ref);
      long references =
          scalar(db, "SELECT COUNT(*) FROM model_message_references WHERE payload_id=?", ref.id());
      if (references == 0
          && scalar(db, "SELECT staged_until FROM model_message_payloads WHERE id=?", ref.id())
              <= clock.millis()) throw rejected();
      try (var insert =
          db.prepareStatement(
              "INSERT OR IGNORE INTO model_message_references(payload_id,kind,owner) VALUES(?,?,?)")) {
        bind(insert, ref.id(), kind.name(), owner);
        insert.executeUpdate();
      }
      return null;
    });
  }

  public void release(Scope scope, Ref ref, ReferenceKind kind, String owner) {
    validate(scope);
    identifier(owner);
    if (kind == null) throw rejected();
    transaction(db -> {
      read(db, scope, ref);
      try (var delete =
          db.prepareStatement(
              "DELETE FROM model_message_references WHERE payload_id=? AND kind=? AND owner=?")) {
        bind(delete, ref.id(), kind.name(), owner);
        delete.executeUpdate();
      }
      return null;
    });
  }

  /** Invoked by retention maintenance, never by an independent background scheduler. */
  public int collectExpired() {
    return transaction(db -> {
      try (var delete =
          db.prepareStatement(
              "DELETE FROM model_message_payloads WHERE staged_until<=? AND NOT EXISTS(SELECT 1 FROM model_message_references WHERE payload_id=model_message_payloads.id)")) {
        delete.setLong(1, clock.millis());
        return delete.executeUpdate();
      }
    });
  }

  public Usage usage(Scope scope, String run) {
    validate(scope);
    identifier(run);
    return transaction(db ->
        new Usage(
            scalar(db, "SELECT COALESCE(SUM(size),0) FROM model_message_payloads"),
            scalar(
                db,
                "SELECT COALESCE(SUM(bytes),0) FROM model_message_run_usage WHERE project=? AND root_path=? AND conversation=? AND run=?",
                scope.project(),
                scope.root(),
                scope.conversation(),
                run),
            scalar(db, "SELECT COUNT(*) FROM model_message_payloads"),
            scalar(db, "SELECT COUNT(*) FROM model_message_references")));
  }

  private final DataSource source;
  private final ModelMessageCodec codec = new ModelMessageCodec();
  private final java.time.Clock clock;
  private final Limits limits;

  @org.springframework.beans.factory.annotation.Autowired
  public ModelMessagePayloadStore(
      @org.springframework.beans.factory.annotation.Qualifier("memoryConsolidationDataSource")
          DataSource source) {
    this(
        source,
        java.time.Clock.systemUTC(),
        new Limits(64L * 1024 * 1024, 512L * 1024 * 1024, java.time.Duration.ofHours(1)));
  }

  ModelMessagePayloadStore(DataSource source, java.time.Clock clock, Limits limits) {
    this.source = java.util.Objects.requireNonNull(source);
    this.clock = java.util.Objects.requireNonNull(clock);
    this.limits = java.util.Objects.requireNonNull(limits);
    if (limits.runBytes() <= 0
        || limits.totalBytes() <= 0
        || limits.stagingLifetime() == null
        || limits.stagingLifetime().isNegative()
        || limits.stagingLifetime().isZero()
        || limits.stagingLifetime().compareTo(java.time.Duration.ofDays(1)) > 0) throw rejected();
    transaction(db -> {
      execute(db, "CREATE TABLE IF NOT EXISTS model_message_schema(version INTEGER NOT NULL)");
      long version = scalar(db, "SELECT COALESCE(MAX(version),0) FROM model_message_schema");
      if (version < 0 || version > 3) throw rejected();
      if (version == 0) {
        execute(
            db,
            "CREATE TABLE model_message_payloads(id TEXT PRIMARY KEY,project TEXT NOT NULL,root_path TEXT NOT NULL,conversation TEXT NOT NULL,run TEXT NOT NULL,message_key TEXT NOT NULL,sha256 TEXT NOT NULL,payload BLOB NOT NULL,size INTEGER NOT NULL,UNIQUE(project,root_path,conversation,run,message_key))");
        execute(db, "INSERT INTO model_message_schema(version) VALUES(1)");
      }
      if (version <= 1) {
        // Unknown references in the prototype v1 schema must be retained conservatively.
        execute(
            db,
            "ALTER TABLE model_message_payloads ADD COLUMN staged_until INTEGER NOT NULL DEFAULT 9223372036854775807");
        execute(
            db,
            "CREATE TABLE model_message_references(payload_id TEXT NOT NULL REFERENCES model_message_payloads(id) ON DELETE RESTRICT,kind TEXT NOT NULL,owner TEXT NOT NULL,PRIMARY KEY(payload_id,kind,owner))");
        execute(
            db,
            "CREATE TABLE model_message_run_usage(project TEXT NOT NULL,root_path TEXT NOT NULL,conversation TEXT NOT NULL,run TEXT NOT NULL,bytes INTEGER NOT NULL,PRIMARY KEY(project,root_path,conversation,run))");
        execute(
            db,
            "INSERT INTO model_message_run_usage SELECT project,root_path,conversation,run,SUM(size) FROM model_message_payloads GROUP BY project,root_path,conversation,run");
        execute(
            db, "CREATE INDEX model_message_payloads_gc ON model_message_payloads(staged_until)");
        execute(db, "UPDATE model_message_schema SET version=2");
      }
      if (version <= 2) {
        execute(
            db,
            "CREATE TABLE model_message_receipts(project TEXT NOT NULL,root_path TEXT NOT NULL,conversation TEXT NOT NULL,run TEXT NOT NULL,message_key TEXT NOT NULL,payload_id TEXT NOT NULL,sha256 TEXT NOT NULL,PRIMARY KEY(project,root_path,conversation,run,message_key))");
        execute(
            db,
            "INSERT INTO model_message_receipts SELECT project,root_path,conversation,run,message_key,id,sha256 FROM model_message_payloads");
        execute(db, "UPDATE model_message_schema SET version=3");
      }
      if (scalar(db, "SELECT COUNT(*) FROM model_message_schema") != 1) throw rejected();
      // Verify even existing schemas before accepting a write; do not reinterpret future versions.
      execute(
          db,
          "SELECT id,project,root_path,conversation,run,message_key,sha256,payload,size,staged_until FROM model_message_payloads LIMIT 0");
      execute(db, "SELECT payload_id,kind,owner FROM model_message_references LIMIT 0");
      execute(
          db,
          "SELECT project,root_path,conversation,run,bytes FROM model_message_run_usage LIMIT 0");
      execute(
          db,
          "SELECT project,root_path,conversation,run,message_key,payload_id,sha256 FROM model_message_receipts LIMIT 0");
      return null;
    });
  }

  public Ref save(Scope scope, String run, String key, Message message) {
    validate(scope);
    identifier(run);
    identifier(key);
    byte[] bytes = codec.encode(message);
    String hash = digest(bytes);
    return transaction(db -> {
      try (var query =
          db.prepareStatement(
              "SELECT payload_id,sha256 FROM model_message_receipts WHERE project=? AND root_path=? AND conversation=? AND run=? AND message_key=?")) {
        bind(query, scope.project(), scope.root(), scope.conversation(), run, key);
        try (var row = query.executeQuery()) {
          if (row.next()) {
            var ref = new Ref(row.getString(1), row.getString(2));
            if (!hash.equals(ref.sha256())) throw rejected();
            if (!java.util.Arrays.equals(bytes, read(db, scope, ref))) throw rejected();
            return ref;
          }
        }
      }
      long runBytes =
          scalar(
              db,
              "SELECT COALESCE(SUM(bytes),0) FROM model_message_run_usage WHERE project=? AND root_path=? AND conversation=? AND run=?",
              scope.project(),
              scope.root(),
              scope.conversation(),
              run);
      long totalBytes = scalar(db, "SELECT COALESCE(SUM(size),0) FROM model_message_payloads");
      if (runBytes < 0
          || totalBytes < 0
          || runBytes > limits.runBytes() - bytes.length
          || totalBytes > limits.totalBytes() - bytes.length) throw rejected();
      var ref = new Ref(UUID.randomUUID().toString(), hash);
      try (var insert =
          db.prepareStatement(
              "INSERT INTO model_message_payloads(id,project,root_path,conversation,run,message_key,sha256,payload,size,staged_until) VALUES(?,?,?,?,?,?,?,?,?,?)")) {
        bind(
            insert,
            ref.id(),
            scope.project(),
            scope.root(),
            scope.conversation(),
            run,
            key,
            hash,
            bytes,
            bytes.length,
            Math.addExact(clock.millis(), limits.stagingLifetime().toMillis()));
        insert.executeUpdate();
      }
      try (var receipt =
          db.prepareStatement(
              "INSERT INTO model_message_receipts(project,root_path,conversation,run,message_key,payload_id,sha256) VALUES(?,?,?,?,?,?,?)")) {
        bind(
            receipt, scope.project(), scope.root(), scope.conversation(), run, key, ref.id(), hash);
        receipt.executeUpdate();
      }
      try (var update =
          db.prepareStatement(
              "INSERT INTO model_message_run_usage(project,root_path,conversation,run,bytes) VALUES(?,?,?,?,?) ON CONFLICT(project,root_path,conversation,run) DO UPDATE SET bytes=excluded.bytes")) {
        bind(
            update,
            scope.project(),
            scope.root(),
            scope.conversation(),
            run,
            Math.addExact(runBytes, bytes.length));
        update.executeUpdate();
      }
      return ref;
    });
  }

  public Message load(Scope scope, Ref ref) {
    validate(scope);
    return transaction(db -> codec.decode(read(db, scope, ref)));
  }

  /** Restore only a reference published by the owning continuation or checkpoint. */
  public Message load(Scope scope, Ref ref, ReferenceKind kind, String owner) {
    validate(scope);
    identifier(owner);
    if (kind == null) throw rejected();
    return transaction(db -> {
      byte[] bytes = read(db, scope, ref);
      if (scalar(
              db,
              "SELECT COUNT(*) FROM model_message_references WHERE payload_id=? AND kind=? AND owner=?",
              ref.id(),
              kind.name(),
              owner)
          != 1) throw rejected();
      return codec.decode(bytes);
    });
  }

  private static byte[] read(Connection db, Scope scope, Ref ref) throws SQLException {
    if (ref == null) throw rejected();
    identifier(ref.id());
    if (ref.sha256() == null || !ref.sha256().matches("[0-9a-f]{64}")) throw rejected();
    try (var query =
        db.prepareStatement(
            "SELECT sha256,payload,size,length(payload) FROM model_message_payloads WHERE id=? AND project=? AND root_path=? AND conversation=?")) {
      bind(query, ref.id(), scope.project(), scope.root(), scope.conversation());
      try (var row = query.executeQuery()) {
        if (!row.next()) throw rejected();
        long size = row.getLong(3);
        if (size < 0
            || size > ModelMessageCodec.MAX_BYTES
            || size != row.getLong(4)
            || !ref.sha256().equals(row.getString(1))) throw rejected();
        byte[] bytes = row.getBytes(2);
        if (bytes == null || bytes.length != size || !ref.sha256().equals(digest(bytes)))
          throw rejected();
        return bytes;
      }
    }
  }

  private static String digest(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw rejected();
    }
  }

  private static void validate(Scope scope) {
    if (scope == null) throw rejected();
    identifier(scope.project());
    identifier(scope.root());
    identifier(scope.conversation());
  }

  private static void identifier(String value) {
    if (value == null || value.isBlank() || value.length() > 1024 || value.indexOf('\0') >= 0)
      throw rejected();
  }

  private static void bind(java.sql.PreparedStatement statement, Object... values)
      throws SQLException {
    for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
  }

  private static void execute(Connection db, String sql) throws SQLException {
    try (var statement = db.createStatement()) {
      statement.execute(sql);
    }
  }

  private static long scalar(Connection db, String sql, Object... values) throws SQLException {
    try (var statement = db.prepareStatement(sql)) {
      bind(statement, values);
      try (var row = statement.executeQuery()) {
        if (!row.next()) throw rejected();
        return row.getLong(1);
      }
    }
  }

  @FunctionalInterface
  private interface Work<T> {
    T apply(Connection connection) throws SQLException;
  }

  private <T> T transaction(Work<T> work) {
    try (var db = source.getConnection()) {
      execute(db, "PRAGMA foreign_keys=ON");
      execute(db, "BEGIN IMMEDIATE");
      try {
        T result = work.apply(db);
        execute(db, "COMMIT");
        return result;
      } catch (SQLException | RuntimeException failure) {
        try {
          execute(db, "ROLLBACK");
        } catch (SQLException ignored) {
          /* Connection close also rolls back. */
        }
        throw rejected();
      }
    } catch (SQLException | RuntimeException failure) {
      throw rejected();
    }
  }

  private static IllegalStateException rejected() {
    return new IllegalStateException("Model message persistence rejected");
  }
}
