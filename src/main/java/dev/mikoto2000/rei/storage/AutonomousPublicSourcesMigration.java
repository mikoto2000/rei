package dev.mikoto2000.rei.storage;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Explicit publication-use permissions and retained source versions, separate from private memory.
 */
final class AutonomousPublicSourcesMigration {
  private AutonomousPublicSourcesMigration() {}

  static void apply(Connection db) throws SQLException {
    try (var sql = db.createStatement()) {
      sql.execute(
          """
          CREATE TABLE IF NOT EXISTS autonomous_public_sources(
            id TEXT NOT NULL,
            revision INTEGER NOT NULL CHECK(revision>0),
            is_current INTEGER NOT NULL CHECK(is_current IN (0,1)),
            body TEXT NOT NULL CHECK(length(CAST(body AS BLOB)) BETWEEN 1 AND 8192),
            public_use INTEGER NOT NULL CHECK(public_use IN (0,1)),
            updated INTEGER NOT NULL,
            actor TEXT NOT NULL CHECK(length(actor) BETWEEN 1 AND 128),
            PRIMARY KEY(id,revision))
          """);
      sql.execute(
          """
          CREATE UNIQUE INDEX IF NOT EXISTS autonomous_source_current
          ON autonomous_public_sources(id) WHERE is_current=1
          """);
      sql.execute(
          """
          CREATE INDEX IF NOT EXISTS autonomous_source_allowed
          ON autonomous_public_sources(is_current,public_use,updated DESC,id)
          """);
    }
    verify(db);
  }

  static void verify(Connection db) throws SQLException {
    try (var sql = db.createStatement()) {
      sql.executeQuery(
              "SELECT id,revision,is_current,body,public_use,updated,actor FROM autonomous_public_sources LIMIT 0")
          .close();
      try (var rows =
          sql.executeQuery(
              """
              SELECT COUNT(*) FROM sqlite_schema WHERE type='index' AND name IN
              ('autonomous_source_current','autonomous_source_allowed')
              """)) {
        if (!rows.next() || rows.getInt(1) != 2)
          throw new SQLException("Autonomous public-source indexes are missing");
      }
    }
  }
}
