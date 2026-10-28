package dev.mikoto2000.rei.storage;

import java.sql.Connection;
import java.sql.SQLException;

/** Expiring run-scoped search cursors and reading observations. */
final class ReadingStateMigration {
  static void apply(Connection db) throws SQLException {
    try (var sql = db.createStatement()) {
      sql.execute(
          "CREATE TABLE reading_state(scope TEXT NOT NULL,kind TEXT NOT NULL,id TEXT NOT NULL,expires INTEGER NOT NULL,bytes INTEGER NOT NULL,record TEXT NOT NULL,PRIMARY KEY(scope,kind,id))");
      sql.execute("CREATE INDEX reading_state_expiry ON reading_state(expires)");
    }
  }

  static void verify(Connection db) throws SQLException {
    try (var sql = db.createStatement()) {
      sql.executeQuery("SELECT scope,kind,id,expires,bytes,record FROM reading_state LIMIT 0")
          .close();
    }
  }
}
