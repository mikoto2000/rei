package dev.mikoto2000.rei.storage;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Fixed publication records and durable execution ownership; migration performs no external I/O.
 */
final class AutonomousPublicationMigration {
  private AutonomousPublicationMigration() {}

  static void apply(Connection db) throws SQLException {
    try (var sql = db.createStatement()) {
      sql.execute(
          """
          CREATE TABLE IF NOT EXISTS autonomous_publications(
            draft_id TEXT PRIMARY KEY NOT NULL REFERENCES autonomous_drafts(id),
            target_did TEXT NOT NULL CHECK(length(target_did) BETWEEN 7 AND 2048 AND target_did LIKE 'did:%'),
            record_key TEXT NOT NULL CHECK(length(record_key)=13
              AND substr(record_key,1,1) GLOB '[234567abcdefghij]'
              AND record_key NOT GLOB '*[^234567abcdefghijklmnopqrstuvwxyz]*'),
            revision INTEGER NOT NULL CHECK(typeof(revision)='integer' AND revision>0),
            body_hash TEXT NOT NULL CHECK(length(body_hash)=64 AND body_hash NOT GLOB '*[^0-9a-f]*'),
            record_json TEXT NOT NULL CHECK(length(record_json) BETWEEN 1 AND 8192),
            record_hash TEXT NOT NULL CHECK(length(record_hash)=64 AND record_hash NOT GLOB '*[^0-9a-f]*'),
            token TEXT UNIQUE CHECK(token IS NULL OR length(token) BETWEEN 1 AND 128),
            created INTEGER NOT NULL CHECK(typeof(created)='integer'),
            updated INTEGER NOT NULL CHECK(typeof(updated)='integer'),
            UNIQUE(target_did,record_key),
            FOREIGN KEY(draft_id,revision) REFERENCES autonomous_draft_revisions(draft_id,revision))
          """);
    }
    verify(db);
  }

  static void verify(Connection db) throws SQLException {
    try (var sql = db.createStatement()) {
      sql.executeQuery(
              """
              SELECT draft_id,target_did,record_key,revision,body_hash,record_json,record_hash,token,created,updated
              FROM autonomous_publications LIMIT 0
              """)
          .close();
    }
  }
}
