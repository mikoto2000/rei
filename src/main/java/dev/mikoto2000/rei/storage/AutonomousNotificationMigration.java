package dev.mikoto2000.rei.storage;

import java.sql.Connection;
import java.sql.SQLException;

/** Durable local notification intent, committed with the owned draft state. */
final class AutonomousNotificationMigration {
  private AutonomousNotificationMigration() {}

  static void apply(Connection db) throws SQLException {
    try (var sql = db.createStatement()) {
      sql.execute(
          """
          CREATE TABLE IF NOT EXISTS autonomous_notification_outbox(
            draft_id TEXT PRIMARY KEY NOT NULL REFERENCES autonomous_drafts(id),
            review_required INTEGER NOT NULL CHECK(typeof(review_required)='integer' AND review_required IN (0,1)),
            created INTEGER NOT NULL CHECK(typeof(created)='integer'),
            pending INTEGER NOT NULL CHECK(typeof(pending)='integer' AND pending IN (0,1)),
            delivered_at INTEGER CHECK(delivered_at IS NULL OR typeof(delivered_at)='integer'),
            CHECK(pending=1 OR delivered_at IS NOT NULL))
          """);
      sql.execute(
          """
          CREATE INDEX IF NOT EXISTS autonomous_notification_pending
          ON autonomous_notification_outbox(pending,created,draft_id)
          """);
      sql.execute(
          """
          INSERT OR IGNORE INTO autonomous_notification_outbox
          SELECT id,CASE WHEN state='PENDING_REVIEW' THEN 1 ELSE 0 END,created,1,NULL
          FROM autonomous_drafts
          """);
    }
    verify(db);
  }

  static void verify(Connection db) throws SQLException {
    try (var sql = db.createStatement()) {
      sql.executeQuery(
              "SELECT draft_id,review_required,created,pending,delivered_at FROM autonomous_notification_outbox LIMIT 0")
          .close();
    }
  }
}
