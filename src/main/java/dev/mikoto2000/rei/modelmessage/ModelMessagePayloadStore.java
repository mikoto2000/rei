package dev.mikoto2000.rei.modelmessage;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.ai.chat.messages.Message;

/** Durable protocol messages; callers publish references before making them visible. */
public final class ModelMessagePayloadStore {
  public record Scope(String project, String rootRun, String conversation) {}
  public record Ref(String id, String sha256) {}
  public record Limits(long runBytes, long totalBytes, java.time.Duration stagingLifetime) {}
  public record Usage(long payloadBytes, long runCumulativeBytes, long payloadCount, long referenceCount) {}
  public enum ReferenceKind { HISTORY, RUN, APPROVAL, CHECKPOINT }

  public void publish(Scope scope, Ref ref, ReferenceKind kind, String owner) { throw rejected(); }
  public void release(Scope scope, Ref ref, ReferenceKind kind, String owner) { throw rejected(); }
  public int collectExpired() { return 0; }
  public Usage usage(Scope scope, String run) { return new Usage(0, 0, 0, 0); }

  private final DataSource source;
  private final ModelMessageCodec codec = new ModelMessageCodec();

  public ModelMessagePayloadStore(DataSource source) {
    this.source = java.util.Objects.requireNonNull(source);
    transaction(db -> {
      execute(db, "CREATE TABLE IF NOT EXISTS model_message_schema(version INTEGER NOT NULL)");
      long version = scalar(db, "SELECT COALESCE(MAX(version),0) FROM model_message_schema");
      if (version != 0 && version != 1) throw rejected();
      if (version == 0) {
        execute(db, "CREATE TABLE model_message_payloads(id TEXT PRIMARY KEY,project TEXT NOT NULL,root_run TEXT NOT NULL,conversation TEXT NOT NULL,run TEXT NOT NULL,message_key TEXT NOT NULL,sha256 TEXT NOT NULL,payload BLOB NOT NULL,size INTEGER NOT NULL,UNIQUE(project,root_run,conversation,run,message_key))");
        execute(db, "INSERT INTO model_message_schema(version) VALUES(1)");
      }
      // Verify even existing schemas before accepting a write; do not reinterpret future versions.
      execute(db, "SELECT id,project,root_run,conversation,run,message_key,sha256,payload,size FROM model_message_payloads LIMIT 0");
      return null;
    });
  }

  ModelMessagePayloadStore(DataSource source, java.time.Clock clock, Limits limits) { this(source); }

  public Ref save(Scope scope, String run, String key, Message message) {
    validate(scope);
    identifier(run);
    identifier(key);
    byte[] bytes = codec.encode(message);
    String hash = digest(bytes);
    return transaction(db -> {
      try (var query = db.prepareStatement("SELECT id,sha256 FROM model_message_payloads WHERE project=? AND root_run=? AND conversation=? AND run=? AND message_key=?")) {
        bind(query, scope.project(), scope.rootRun(), scope.conversation(), run, key);
        try (var row = query.executeQuery()) {
          if (row.next()) {
            var ref = new Ref(row.getString(1), row.getString(2));
            if (!hash.equals(ref.sha256())) throw rejected();
            if (!java.util.Arrays.equals(bytes, read(db, scope, ref))) throw rejected();
            return ref;
          }
        }
      }
      var ref = new Ref(UUID.randomUUID().toString(), hash);
      try (var insert = db.prepareStatement("INSERT INTO model_message_payloads(id,project,root_run,conversation,run,message_key,sha256,payload,size) VALUES(?,?,?,?,?,?,?,?,?)")) {
        bind(insert, ref.id(), scope.project(), scope.rootRun(), scope.conversation(), run, key, hash, bytes, bytes.length);
        insert.executeUpdate();
      }
      return ref;
    });
  }

  public Message load(Scope scope, Ref ref) {
    validate(scope);
    return transaction(db -> codec.decode(read(db, scope, ref)));
  }

  private static byte[] read(Connection db, Scope scope, Ref ref) throws SQLException {
    if (ref == null) throw rejected();
    identifier(ref.id());
    if (ref.sha256() == null || !ref.sha256().matches("[0-9a-f]{64}")) throw rejected();
    try (var query = db.prepareStatement("SELECT sha256,payload,size FROM model_message_payloads WHERE id=? AND project=? AND root_run=? AND conversation=?")) {
      bind(query, ref.id(), scope.project(), scope.rootRun(), scope.conversation());
      try (var row = query.executeQuery()) {
        if (!row.next()) throw rejected();
        long size = row.getLong(3);
        if (size < 0 || size > ModelMessageCodec.MAX_BYTES || !ref.sha256().equals(row.getString(1))) throw rejected();
        byte[] bytes = row.getBytes(2);
        if (bytes == null || bytes.length != size || !ref.sha256().equals(digest(bytes))) throw rejected();
        return bytes;
      }
    }
  }

  private static String digest(byte[] bytes) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    catch (java.security.NoSuchAlgorithmException impossible) { throw rejected(); }
  }

  private static void validate(Scope scope) {
    if (scope == null) throw rejected();
    identifier(scope.project());
    identifier(scope.rootRun());
    identifier(scope.conversation());
  }

  private static void identifier(String value) {
    if (value == null || value.isBlank() || value.length() > 1024 || value.indexOf('\0') >= 0) throw rejected();
  }

  private static void bind(java.sql.PreparedStatement statement, Object... values) throws SQLException {
    for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
  }

  private static void execute(Connection db, String sql) throws SQLException {
    try (var statement = db.createStatement()) { statement.execute(sql); }
  }

  private static long scalar(Connection db, String sql) throws SQLException {
    try (var statement = db.createStatement(); var row = statement.executeQuery(sql)) {
      if (!row.next()) throw rejected();
      return row.getLong(1);
    }
  }

  @FunctionalInterface
  private interface Work<T> { T apply(Connection connection) throws SQLException; }

  private <T> T transaction(Work<T> work) {
    try (var db = source.getConnection()) {
      execute(db, "BEGIN IMMEDIATE");
      try {
        T result = work.apply(db);
        execute(db, "COMMIT");
        return result;
      } catch (SQLException | RuntimeException failure) {
        try { execute(db, "ROLLBACK"); } catch (SQLException ignored) { /* Connection close also rolls back. */ }
        throw rejected();
      }
    } catch (SQLException failure) { throw rejected(); }
  }

  private static IllegalStateException rejected() {
    return new IllegalStateException("Model message persistence rejected");
  }
}
