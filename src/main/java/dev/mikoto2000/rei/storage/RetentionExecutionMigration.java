package dev.mikoto2000.rei.storage;

import java.sql.*;

/** Execution journals contain bounded per-object metadata, never body arrays. */
final class RetentionExecutionMigration {
  static void apply(Connection db)throws SQLException {try(var sql=db.createStatement()) {
    sql.execute("CREATE TABLE retention_executions(id TEXT PRIMARY KEY,plan_id TEXT NOT NULL UNIQUE,snapshot_hash TEXT NOT NULL,status TEXT NOT NULL,created TEXT NOT NULL,updated TEXT NOT NULL)");
    sql.execute("CREATE TABLE retention_execution_items(execution_id TEXT NOT NULL,object_id TEXT NOT NULL,ordinal INTEGER NOT NULL,state TEXT NOT NULL,quarantine_path TEXT NOT NULL,quarantined TEXT,record TEXT NOT NULL,PRIMARY KEY(execution_id,object_id),UNIQUE(execution_id,ordinal))");
    sql.execute("CREATE TABLE retention_purge_approvals(execution_id TEXT PRIMARY KEY,snapshot_hash TEXT NOT NULL,created TEXT NOT NULL)");
    sql.execute("CREATE TABLE activity_segments(id TEXT PRIMARY KEY,scope TEXT NOT NULL,path TEXT NOT NULL UNIQUE,state TEXT NOT NULL,bytes INTEGER NOT NULL,sha256 TEXT NOT NULL,created TEXT NOT NULL,updated TEXT NOT NULL)");
    sql.execute("CREATE UNIQUE INDEX activity_segments_open ON activity_segments(scope) WHERE state='OPEN'");
    sql.execute("CREATE INDEX activity_segments_scope ON activity_segments(scope,created,id)");
    sql.execute("CREATE TABLE retention_automatic_consents(id TEXT PRIMARY KEY,policy_id TEXT NOT NULL,policy_version INTEGER NOT NULL,scope TEXT NOT NULL,max_objects INTEGER NOT NULL,max_bytes INTEGER NOT NULL,interval_seconds INTEGER NOT NULL,record TEXT NOT NULL,snapshot_hash TEXT NOT NULL,status TEXT NOT NULL,created TEXT NOT NULL,last_run TEXT)");
    sql.execute("CREATE TABLE retention_automatic_runs(consent_id TEXT NOT NULL,plan_id TEXT NOT NULL UNIQUE,created TEXT NOT NULL)");
  }}
  static void verify(Connection db)throws SQLException {try(var sql=db.createStatement()) {
    sql.executeQuery("SELECT id,plan_id,snapshot_hash,status,created,updated FROM retention_executions LIMIT 0").close();
    sql.executeQuery("SELECT execution_id,object_id,ordinal,state,quarantine_path,quarantined,record FROM retention_execution_items LIMIT 0").close();
    sql.executeQuery("SELECT execution_id,snapshot_hash,created FROM retention_purge_approvals LIMIT 0").close();
    sql.executeQuery("SELECT id,scope,path,state,bytes,sha256,created,updated FROM activity_segments LIMIT 0").close();
    sql.executeQuery("SELECT id,policy_id,policy_version,scope,max_objects,max_bytes,interval_seconds,record,snapshot_hash,status,created,last_run FROM retention_automatic_consents LIMIT 0").close();
    sql.executeQuery("SELECT consent_id,plan_id,created FROM retention_automatic_runs LIMIT 0").close();
  }}
}
