package dev.mikoto2000.rei.storage;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * データ保存の共通処理に必要なMCP保存移行を扱う。
 *
 * <p>MCP ownership and immutable result index; execution remains in conversation_admissions.
 */
final class McpStorageMigration {
  /** 指定された内容を、現在の対象へ反映する。 */
  static void apply(Connection db) throws SQLException {
    try (var sql = db.createStatement()) {
      sql.execute(
          "CREATE TABLE mcp_requests(principal TEXT NOT NULL,request_hash TEXT NOT NULL,fingerprint TEXT NOT NULL,project_id TEXT NOT NULL,session_id TEXT NOT NULL,run_id TEXT NOT NULL UNIQUE,created_at TEXT NOT NULL,expires_at TEXT NOT NULL,result_json TEXT,result_expires_at TEXT,terminal_state TEXT,total_criteria INTEGER NOT NULL,criteria_json TEXT NOT NULL DEFAULT '[]',admission_error TEXT,state_updated_at TEXT,PRIMARY KEY(principal,request_hash))");
      sql.execute("CREATE TABLE mcp_settings(name TEXT PRIMARY KEY,value TEXT NOT NULL)");
      sql.execute("INSERT INTO mcp_settings VALUES('cursor_key',hex(randomblob(32)))");
      sql.execute(
          "CREATE TABLE mcp_sessions(session_id TEXT PRIMARY KEY,principal TEXT NOT NULL,project_id TEXT NOT NULL)");
      sql.execute(
          "CREATE TABLE mcp_audit(id TEXT PRIMARY KEY,principal TEXT NOT NULL,project_id TEXT,run_id TEXT,request_hash TEXT,operation TEXT NOT NULL,outcome TEXT NOT NULL,created_at TEXT NOT NULL,correlation_id TEXT NOT NULL)");
    }
  }

  /** 要求された条件を満たすことを確認する。 */
  static void verify(Connection db) throws SQLException {
    try (var sql = db.createStatement()) {
      sql.executeQuery(
              "SELECT principal,request_hash,fingerprint,project_id,session_id,run_id,created_at,expires_at,result_json,result_expires_at,terminal_state,total_criteria,criteria_json,admission_error,state_updated_at FROM mcp_requests LIMIT 0")
          .close();
      sql.executeQuery("SELECT name,value FROM mcp_settings LIMIT 0").close();
      sql.executeQuery("SELECT session_id,principal,project_id FROM mcp_sessions LIMIT 0").close();
      sql.executeQuery(
              "SELECT id,principal,project_id,run_id,request_hash,operation,outcome,created_at,correlation_id FROM mcp_audit LIMIT 0")
          .close();
    }
  }
}
