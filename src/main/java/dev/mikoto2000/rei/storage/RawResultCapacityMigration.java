package dev.mikoto2000.rei.storage;

import java.sql.Connection;
import java.sql.SQLException;

/** A small covering index isolates capacity accounting from the much larger event registry. */
final class RawResultCapacityMigration {
  private RawResultCapacityMigration() {}

  static void apply(Connection db) throws SQLException {
    try (var sql = db.createStatement()) {
      sql.execute(
          "CREATE INDEX IF NOT EXISTS stored_objects_raw_capacity ON stored_objects(size) WHERE kind='RAW_RESULT' AND status<>'PURGED'");
    }
    verify(db);
  }

  static void verify(Connection db) throws SQLException {
    try (var sql = db.createStatement();
        var rows =
            sql.executeQuery(
                "SELECT size FROM stored_objects INDEXED BY stored_objects_raw_capacity WHERE kind='RAW_RESULT' AND status<>'PURGED' LIMIT 0")) {
      if (rows.next()) throw new SQLException("Unexpected capacity index verification row");
    }
  }
}
