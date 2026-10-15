package dev.mikoto2000.rei.storage;

import java.sql.*;

final class ReviewCoverageMigration {
  static void apply(Connection db) throws SQLException {
    try (var sql = db.createStatement()) {
      sql.execute(
          "CREATE TABLE review_jobs(id TEXT PRIMARY KEY,project TEXT NOT NULL,conversation TEXT NOT NULL,updated TEXT NOT NULL,record TEXT NOT NULL)");
      sql.execute("CREATE INDEX review_jobs_scope ON review_jobs(project,conversation,updated,id)");
    }
  }

  static void verify(Connection db) throws SQLException {
    try (var sql = db.createStatement()) {
      sql.executeQuery("SELECT id,project,conversation,updated,record FROM review_jobs LIMIT 0")
          .close();
    }
  }
}
