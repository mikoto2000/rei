package dev.mikoto2000.rei.storage;

import java.sql.Connection;
import java.sql.SQLException;

/** Binds a regeneration reservation to the reviewed content revision. */
final class AutonomousRegenerationMigration {
  private AutonomousRegenerationMigration() {}

  static void apply(Connection db) throws SQLException {
    boolean present = false;
    try (var sql = db.createStatement();
        var rows = sql.executeQuery("PRAGMA table_info(autonomous_opportunities)")) {
      while (rows.next()) present |= "expected_revision".equals(rows.getString("name"));
    }
    if (!present) {
      try (var sql = db.createStatement()) {
        sql.execute(
            """
            ALTER TABLE autonomous_opportunities ADD COLUMN expected_revision INTEGER
            CHECK(expected_revision IS NULL OR
              (typeof(expected_revision)='integer' AND expected_revision>0))
            """);
      }
    }
    verify(db);
  }

  static void verify(Connection db) throws SQLException {
    try (var sql = db.createStatement()) {
      sql.executeQuery("SELECT expected_revision FROM autonomous_opportunities LIMIT 0").close();
    }
  }
}
