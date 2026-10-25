package dev.mikoto2000.rei.secretary;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Shared bounded access to the existing snapshot table; no additional state or scheduler. */
final class SecretarySnapshotStore {
  private final SecretaryDatabase database;

  SecretarySnapshotStore(SecretaryDatabase database) {
    this.database = database;
  }

  String byKey(String scope, String key) {
    return one(false, scope, key);
  }

  String byId(String scope, String id) {
    return one(true, scope, id);
  }

  private String one(boolean byId, String scope, String value) {
    return database.read(
        db -> {
          try (var q =
              byId
                  ? db.prepareStatement(
                      "SELECT record FROM secretary_briefings WHERE project=? AND id=?")
                  : db.prepareStatement(
                      "SELECT record FROM secretary_briefings WHERE project=? AND request_key=?")) {
            q.setString(1, scope);
            q.setString(2, value);
            try (var r = q.executeQuery()) {
              return r.next() ? r.getString(1) : null;
            }
          }
        });
  }

  void save(String id, String scope, String key, Instant generated, Object record) {
    database.transaction(
        db -> {
          try (var q =
              db.prepareStatement("INSERT OR IGNORE INTO secretary_briefings VALUES(?,?,?,?,?)")) {
            q.setString(1, id);
            q.setString(2, scope);
            q.setString(3, key);
            q.setLong(4, generated.toEpochMilli());
            q.setString(5, SecretaryJson.JSON.writeValueAsString(record));
            q.executeUpdate();
          }
          try (var q =
              db.prepareStatement(
                  "DELETE FROM secretary_briefings WHERE project=? AND id NOT IN (SELECT id FROM secretary_briefings WHERE project=? ORDER BY generated DESC,rowid DESC LIMIT 100)")) {
            q.setString(1, scope);
            q.setString(2, scope);
            q.executeUpdate();
          }
          return null;
        });
  }

  List<String> list(String scope, int limit) {
    if (limit < 1 || limit > 100)
      throw new IllegalArgumentException("History limit must be 1..100");
    return database.read(
        db -> {
          var values = new ArrayList<String>();
          try (var q =
              db.prepareStatement(
                  "SELECT record FROM secretary_briefings WHERE project=? ORDER BY generated DESC,rowid DESC LIMIT ?")) {
            q.setString(1, scope);
            q.setInt(2, limit);
            try (var r = q.executeQuery()) {
              while (r.next()) values.add(r.getString(1));
            }
          }
          return List.copyOf(values);
        });
  }

  int delete(String scope) {
    return database.transaction(
        db -> {
          try (var q = db.prepareStatement("DELETE FROM secretary_briefings WHERE project=?")) {
            q.setString(1, scope);
            return q.executeUpdate();
          }
        });
  }

  boolean noticeExists(String id) {
    return database.read(
        db -> {
          try (var q = db.prepareStatement("SELECT 1 FROM secretary_notices WHERE id=?")) {
            q.setString(1, id);
            try (var r = q.executeQuery()) {
              return r.next();
            }
          }
        });
  }

  static <T> T decode(String value, Class<T> type) {
    if (value == null) return null;
    try {
      return SecretaryJson.JSON.readValue(value, type);
    } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
      throw new IllegalStateException("Cannot read saved briefing", invalid);
    }
  }
}
