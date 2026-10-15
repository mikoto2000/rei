package dev.mikoto2000.rei.storage;

import java.sql.*;

/** A bounded scope index keeps child/Resume lookup independent of growing range JSON. */
final class ReviewRuntimeMigration {
  static void apply(Connection db) throws Exception {
    try (var sql = db.createStatement()) {
      sql.execute(
          "CREATE TABLE review_participants(job TEXT NOT NULL,project TEXT NOT NULL,conversation TEXT NOT NULL,run TEXT NOT NULL,PRIMARY KEY(job,project,conversation,run))");
      sql.execute(
          "CREATE INDEX review_participant_scope ON review_participants(project,conversation,run,job)");
    }
    try (var sql = db.createStatement();
        var rows = sql.executeQuery("SELECT record FROM review_jobs")) {
      while (rows.next()) {
        var job =
            StorageDatabase.JSON.readValue(
                rows.getString(1), dev.mikoto2000.rei.review.ReviewCoverageLedger.Job.class);
        dev.mikoto2000.rei.review.ReviewCoverageLedger.writeParticipants(db, job);
      }
    }
  }

  static void verify(Connection db) throws SQLException {
    try (var sql = db.createStatement()) {
      sql.executeQuery("SELECT job,project,conversation,run FROM review_participants LIMIT 0")
          .close();
    }
  }
}
