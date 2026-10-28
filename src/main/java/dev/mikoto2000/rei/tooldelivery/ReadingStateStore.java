package dev.mikoto2000.rei.tooldelivery;

import dev.mikoto2000.rei.tooldelivery.ReadingProblem;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

/** Bounded SQLite state using the existing serialized writer and startup migration. */
public final class ReadingStateStore {
  @FunctionalInterface
  public interface Work<T> { T run(java.sql.Connection connection) throws Exception; }
  @FunctionalInterface
  public interface Access { Object run(boolean write, Work<?> work); }
  private final Access database;
  private final Clock clock;

  public ReadingStateStore(Access database) {
    this(database, Clock.systemUTC());
  }

  public ReadingStateStore(Access database, Clock clock) {
    this.database = database;
    this.clock = clock;
  }

  public static String scope(ReadingRun owner) {
    if (owner == null) throw ReadingProblem.fail("RUN_REQUIRED");
    try {
      return ReadingJson.hash(
          ReadingJson.JSON.writeValueAsBytes(
              List.of(
                  owner.runId(),
                  owner.conversationId(),
                  owner.projectRoot().toRealPath().toString(),
                  java.util.Objects.toString(owner.projectId(), ""),
                  owner.sourceName(),
                  owner.modeName())));
    } catch (java.io.IOException error) {
      throw new IllegalArgumentException("Invalid Project root", error);
    }
  }

  public void save(ReadingRun owner, String kind, String id, Object value, Duration lifetime) {
    if (lifetime.isNegative() || lifetime.isZero() || lifetime.compareTo(Duration.ofDays(7)) > 0)
      throw new IllegalArgumentException("Invalid reading retention");
    final String record;
    try {
      record = ReadingJson.JSON.writeValueAsString(value);
    } catch (java.io.IOException error) {
      throw new IllegalArgumentException("Invalid reading state", error);
    }
    int bytes = record.getBytes(StandardCharsets.UTF_8).length;
    if (bytes > 4 * 1024 * 1024) throw ReadingProblem.fail("SNAPSHOT_BUDGET");
    String scope = scope(owner);
    transaction(
        db -> {
          long now = clock.millis();
          try (var delete = db.prepareStatement("DELETE FROM reading_state WHERE expires<?")) {
            delete.setLong(1, now);
            delete.executeUpdate();
          }
          try (var query =
              db.prepareStatement(
                  "SELECT COALESCE(SUM(bytes),0),COUNT(*) FROM reading_state WHERE NOT(scope=? AND kind=? AND id=?)")) {
            query.setString(1, scope);
            query.setString(2, kind);
            query.setString(3, id);
            try (var rows = query.executeQuery()) {
              rows.next();
              if (rows.getLong(1) + bytes > 64L * 1024 * 1024 || rows.getLong(2) >= 2048)
                throw ReadingProblem.fail("SNAPSHOT_BUDGET");
            }
          }
          try (var insert =
              db.prepareStatement(
                  "INSERT INTO reading_state VALUES(?,?,?,?,?,?) ON CONFLICT(scope,kind,id) DO UPDATE SET expires=excluded.expires,bytes=excluded.bytes,record=excluded.record")) {
            insert.setString(1, scope);
            insert.setString(2, kind);
            insert.setString(3, id);
            insert.setLong(4, now + lifetime.toMillis());
            insert.setInt(5, bytes);
            insert.setString(6, record);
            insert.executeUpdate();
          }
          return null;
        });
  }

  public <T> T load(ReadingRun owner, String kind, String id, Class<T> type) {
    if (id == null || id.length() > 128) throw ReadingProblem.fail("INVALID_CURSOR");
    String scope = scope(owner);
    return read(
        db -> {
          try (var query =
              db.prepareStatement(
                  "SELECT record,expires FROM reading_state WHERE scope=? AND kind=? AND id=?")) {
            query.setString(1, scope);
            query.setString(2, kind);
            query.setString(3, id);
            try (var rows = query.executeQuery()) {
              if (!rows.next()) throw ReadingProblem.fail("INVALID_CURSOR");
              if (rows.getLong(2) <= clock.millis()) throw ReadingProblem.fail("CURSOR_EXPIRED");
              return ReadingJson.JSON.readValue(rows.getString(1), type);
            }
          }
        });
  }

  @SuppressWarnings("unchecked")
  private <T> T read(Work<T> work) { return (T) database.run(false, work); }

  @SuppressWarnings("unchecked")
  private <T> T transaction(Work<T> work) { return (T) database.run(true, work); }
}
