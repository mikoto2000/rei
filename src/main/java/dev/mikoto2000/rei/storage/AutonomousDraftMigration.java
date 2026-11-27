package dev.mikoto2000.rei.storage;

import java.sql.Connection;
import java.sql.SQLException;

/** Draft consideration and revision journals; no migration enables external publication. */
final class AutonomousDraftMigration {
  private AutonomousDraftMigration() {}

  static void apply(Connection db) throws SQLException {
    try (var sql = db.createStatement()) {
      sql.execute(
          """
          CREATE TABLE IF NOT EXISTS autonomous_opportunities(
            id TEXT PRIMARY KEY,
            trigger TEXT NOT NULL CHECK(trigger IN ('MANUAL','SCHEDULED')),
            kind TEXT NOT NULL CHECK(kind IN ('NEW','REGENERATE')),
            status TEXT NOT NULL CHECK(status IN
              ('RUNNING','POLICY_SKIPPED','SKIPPED','GENERATED','FAILED','ABORTED')),
            token TEXT,
            created INTEGER NOT NULL,
            updated INTEGER NOT NULL,
            considered INTEGER NOT NULL CHECK(considered IN (0,1)),
            zone TEXT NOT NULL,
            target_did TEXT NOT NULL,
            target_handle TEXT NOT NULL,
            reason TEXT NOT NULL,
            draft_id TEXT,
            CHECK(status<>'RUNNING' OR token IS NOT NULL))
          """);
      sql.execute(
          """
          CREATE UNIQUE INDEX IF NOT EXISTS autonomous_one_active
          ON autonomous_opportunities((1)) WHERE status='RUNNING'
          """);
      sql.execute(
          """
          CREATE INDEX IF NOT EXISTS autonomous_attempt_time
          ON autonomous_opportunities(considered,created)
          """);
      sql.execute(
          """
          CREATE TABLE IF NOT EXISTS autonomous_drafts(
            id TEXT PRIMARY KEY,
            opportunity TEXT NOT NULL UNIQUE REFERENCES autonomous_opportunities(id),
            state TEXT NOT NULL CHECK(state IN
              ('PENDING_REVIEW','REJECTED','EXPIRED','PUBLISHING','PUBLISHED','FAILED','UNKNOWN')),
            revision INTEGER NOT NULL CHECK(revision>0),
            created INTEGER NOT NULL,
            updated INTEGER NOT NULL,
            expires INTEGER NOT NULL,
            target_did TEXT NOT NULL,
            body_hash TEXT NOT NULL,
            snapshot TEXT NOT NULL CHECK(length(snapshot)<=65536))
          """);
      sql.execute(
          """
          CREATE INDEX IF NOT EXISTS autonomous_draft_limits
          ON autonomous_drafts(state,created,expires)
          """);
      sql.execute(
          """
          CREATE TABLE IF NOT EXISTS autonomous_draft_revisions(
            draft_id TEXT NOT NULL REFERENCES autonomous_drafts(id),
            revision INTEGER NOT NULL CHECK(revision>0),
            created INTEGER NOT NULL,
            snapshot TEXT NOT NULL CHECK(length(snapshot)<=65536),
            PRIMARY KEY(draft_id,revision))
          """);
      sql.execute(
          """
          CREATE TABLE IF NOT EXISTS autonomous_audit(
            id TEXT PRIMARY KEY, subject TEXT NOT NULL, operation TEXT NOT NULL,
            reason_code TEXT NOT NULL, actor TEXT, created INTEGER NOT NULL)
          """);
    }
    verify(db);
  }

  static void verify(Connection db) throws SQLException {
    try (var sql = db.createStatement()) {
      sql.executeQuery(
              "SELECT id,trigger,kind,status,token,created,updated,considered,zone,target_did,target_handle,reason,draft_id FROM autonomous_opportunities LIMIT 0")
          .close();
      sql.executeQuery(
              "SELECT id,opportunity,state,revision,created,updated,expires,target_did,body_hash,snapshot FROM autonomous_drafts LIMIT 0")
          .close();
      sql.executeQuery(
              "SELECT draft_id,revision,created,snapshot FROM autonomous_draft_revisions LIMIT 0")
          .close();
      sql.executeQuery(
              "SELECT id,subject,operation,reason_code,actor,created FROM autonomous_audit LIMIT 0")
          .close();
      try (var rows =
          sql.executeQuery(
              """
              SELECT COUNT(*) FROM sqlite_schema WHERE type='index' AND name IN
              ('autonomous_one_active','autonomous_attempt_time','autonomous_draft_limits')
              """)) {
        if (!rows.next() || rows.getInt(1) != 3)
          throw new SQLException("Autonomous draft indexes are missing");
      }
    }
  }
}
