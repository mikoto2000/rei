package dev.mikoto2000.rei.storage;

import java.sql.Connection;
import java.sql.SQLException;

/** Immutable generated reports; source tasks and delivery state retain their existing owners. */
final class SecretaryBriefingMigration {
  static void apply(Connection db) throws SQLException {
    try (var q = db.createStatement()) {
      q.execute(
          "CREATE TABLE secretary_briefings(id TEXT PRIMARY KEY,project TEXT NOT NULL,request_key TEXT NOT NULL,generated INTEGER NOT NULL,record TEXT NOT NULL,UNIQUE(project,request_key))");
      q.execute(
          "CREATE INDEX secretary_briefing_history ON secretary_briefings(project,generated DESC,id)");
    }
  }

  static void verify(Connection db) throws SQLException {
    try (var q = db.createStatement();
        var r =
            q.executeQuery(
                "SELECT id,project,request_key,generated,record FROM secretary_briefings LIMIT 0")) {
      /* schema only */
    }
  }
}
