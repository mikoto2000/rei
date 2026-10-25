package dev.mikoto2000.rei.storage;

import java.sql.Connection;
import java.sql.SQLException;

/** Additive secretary ledger migration. Runs inside the startup backup transaction. */
final class SecretaryMigration {
  static void apply(Connection db) throws SQLException {
    try (var sql = db.createStatement()) {
      sql.execute(
          "CREATE TABLE secretary_tasks(id TEXT PRIMARY KEY,project TEXT NOT NULL,title_key TEXT NOT NULL,status TEXT NOT NULL,due TEXT,priority INTEGER NOT NULL,revision INTEGER NOT NULL,record TEXT NOT NULL)");
      sql.execute(
          "CREATE INDEX secretary_tasks_listing ON secretary_tasks(project,status,priority,due,id)");
      sql.execute(
          "CREATE INDEX secretary_tasks_duplicate ON secretary_tasks(project,title_key,status)");
      sql.execute(
          "CREATE TABLE secretary_sources(project TEXT NOT NULL,event TEXT NOT NULL,ordinal INTEGER NOT NULL,fingerprint TEXT NOT NULL,task TEXT NOT NULL,PRIMARY KEY(project,event,ordinal))");
      sql.execute("CREATE INDEX secretary_sources_task ON secretary_sources(project,task)");
      sql.execute(
          "CREATE TABLE secretary_history(task TEXT NOT NULL,revision INTEGER NOT NULL,record TEXT NOT NULL,PRIMARY KEY(task,revision))");
      sql.execute(
          "CREATE TABLE secretary_inputs(project TEXT NOT NULL,event TEXT NOT NULL,fingerprint TEXT NOT NULL,PRIMARY KEY(project,event))");
    }
  }

  static void verify(Connection db) throws SQLException {
    try (var sql = db.createStatement()) {
      sql.executeQuery(
              "SELECT id,project,title_key,status,due,priority,revision,record FROM secretary_tasks LIMIT 0")
          .close();
      sql.executeQuery(
              "SELECT project,event,ordinal,fingerprint,task FROM secretary_sources LIMIT 0")
          .close();
      sql.executeQuery("SELECT task,revision,record FROM secretary_history LIMIT 0").close();
      sql.executeQuery("SELECT project,event,fingerprint FROM secretary_inputs LIMIT 0").close();
    }
  }
}
