package dev.mikoto2000.rei.storage;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * データ保存の共通処理に必要な会話受付移行を扱う。
 *
 * <p>Canonical conversation admission, execution leases and durable request receipts.
 */
final class ConversationAdmissionMigration {
  /** 指定された内容を、現在の対象へ反映する。 */
  static void apply(Connection db) throws SQLException {
    try (var sql = db.createStatement()) {
      sql.execute(
          "CREATE TABLE conversation_admissions(run_id TEXT PRIMARY KEY,project_id TEXT NOT NULL,session_id TEXT NOT NULL,context TEXT NOT NULL,status TEXT NOT NULL,active INTEGER NOT NULL CHECK(active IN (0,1)),owner_instance TEXT NOT NULL,owner_pid INTEGER NOT NULL,owner_start TEXT,accepted TEXT NOT NULL,started TEXT,completed TEXT)");
      sql.execute(
          "CREATE UNIQUE INDEX conversation_session_owner ON conversation_admissions(project_id,session_id) WHERE active=1");
      sql.execute(
          "CREATE TABLE chat_receipts(key_hash TEXT PRIMARY KEY,fingerprint TEXT NOT NULL,run_id TEXT NOT NULL,expires TEXT NOT NULL)");
    }
  }

  /** 要求された条件を満たすことを確認する。 */
  static void verify(Connection db) throws SQLException {
    try (var sql = db.createStatement()) {
      sql.executeQuery(
              "SELECT run_id,project_id,session_id,context,status,active,owner_instance,owner_pid,owner_start,accepted,started,completed FROM conversation_admissions LIMIT 0")
          .close();
      sql.executeQuery("SELECT key_hash,fingerprint,run_id,expires FROM chat_receipts LIMIT 0")
          .close();
      try (var rows =
          sql.executeQuery(
              "SELECT sql FROM sqlite_schema WHERE type='index' AND tbl_name='conversation_admissions' AND name='conversation_session_owner'")) {
        String expected =
            "CREATE UNIQUE INDEX conversation_session_owner ON conversation_admissions(project_id,session_id) WHERE active=1";
        if (!rows.next() || !expected.equals(rows.getString(1)))
          throw new SQLException("Conversation lease index invalid or missing");
      }
    }
  }
}
