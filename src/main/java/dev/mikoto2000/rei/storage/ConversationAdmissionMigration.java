package dev.mikoto2000.rei.storage;

import java.sql.*;

/** Canonical conversation admission, execution leases and durable request receipts. */
final class ConversationAdmissionMigration {
  static void apply(Connection db) throws SQLException {
    try (var sql=db.createStatement()) {
      sql.execute("CREATE TABLE conversation_admissions(run_id TEXT PRIMARY KEY,project_id TEXT NOT NULL,session_id TEXT NOT NULL,context TEXT NOT NULL,status TEXT NOT NULL,active INTEGER NOT NULL CHECK(active IN (0,1)),owner_instance TEXT NOT NULL,owner_pid INTEGER NOT NULL,owner_start TEXT,accepted TEXT NOT NULL,started TEXT,completed TEXT)");
      sql.execute("CREATE UNIQUE INDEX conversation_session_owner ON conversation_admissions(project_id,session_id) WHERE active=1");
      sql.execute("CREATE TABLE chat_receipts(key_hash TEXT PRIMARY KEY,fingerprint TEXT NOT NULL,run_id TEXT NOT NULL,expires TEXT NOT NULL)");
    }
  }
  static void verify(Connection db) throws SQLException {
    try (var sql=db.createStatement()) {
      sql.executeQuery("SELECT run_id,project_id,session_id,context,status,active,owner_instance,owner_pid,owner_start,accepted,started,completed FROM conversation_admissions LIMIT 0").close();
      sql.executeQuery("SELECT key_hash,fingerprint,run_id,expires FROM chat_receipts LIMIT 0").close();
      try (var rows=sql.executeQuery("SELECT count(*) FROM sqlite_schema WHERE name='conversation_session_owner' AND sql LIKE '%WHERE active=1%'")) {
        if (!rows.next() || rows.getInt(1)!=1) throw new SQLException("Conversation lease index missing");
      }
    }
  }
}
