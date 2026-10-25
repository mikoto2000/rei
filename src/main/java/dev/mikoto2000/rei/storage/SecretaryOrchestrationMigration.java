package dev.mikoto2000.rei.storage;

import java.sql.Connection;
import java.sql.SQLException;

final class SecretaryOrchestrationMigration {
  static void apply(Connection db) throws SQLException {
    try (var sql = db.createStatement()) {
      sql.execute(
          "CREATE TABLE secretary_orchestration(project TEXT PRIMARY KEY,record TEXT NOT NULL)");
      sql.execute(
          "CREATE TABLE secretary_suggestions(id TEXT PRIMARY KEY,project TEXT NOT NULL,task TEXT NOT NULL,rule TEXT NOT NULL,priority INTEGER NOT NULL,status TEXT NOT NULL,next INTEGER NOT NULL,updated INTEGER NOT NULL,record TEXT NOT NULL,UNIQUE(project,task,rule))");
      sql.execute(
          "CREATE INDEX secretary_suggestion_list ON secretary_suggestions(project,priority,updated,id)");
      sql.execute(
          "CREATE TABLE secretary_suggestion_history(sequence INTEGER PRIMARY KEY AUTOINCREMENT,project TEXT NOT NULL,suggestion TEXT NOT NULL,action TEXT NOT NULL,revision INTEGER NOT NULL,at INTEGER NOT NULL,detail TEXT NOT NULL)");
      sql.execute(
          "CREATE INDEX secretary_suggestion_history_owner ON secretary_suggestion_history(project,suggestion,sequence)");
      sql.execute(
          "CREATE INDEX secretary_notice_created ON secretary_notices(project,created DESC,id)");
      sql.execute(
          "CREATE INDEX secretary_notice_held ON secretary_notices(status,project,created,id)");
    }
  }

  static void verify(Connection db) throws SQLException {
    try (var sql = db.createStatement()) {
      sql.executeQuery("SELECT project,record FROM secretary_orchestration LIMIT 0").close();
      sql.executeQuery(
              "SELECT id,project,task,rule,priority,status,next,updated,record FROM secretary_suggestions LIMIT 0")
          .close();
      sql.executeQuery(
              "SELECT sequence,project,suggestion,action,revision,at,detail FROM secretary_suggestion_history LIMIT 0")
          .close();
    }
  }
}
