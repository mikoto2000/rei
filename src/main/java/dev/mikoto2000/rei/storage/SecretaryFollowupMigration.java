package dev.mikoto2000.rei.storage;

import java.sql.Connection;
import java.sql.SQLException;

final class SecretaryFollowupMigration {
  static void apply(Connection db) throws SQLException {
    try (var sql = db.createStatement()) {
      sql.execute(
          "CREATE TABLE secretary_settings(project TEXT PRIMARY KEY,record TEXT NOT NULL,configured INTEGER NOT NULL)");
      sql.execute(
          "CREATE TABLE secretary_followup_state(key TEXT PRIMARY KEY,value INTEGER NOT NULL)");
      sql.execute(
          "CREATE TABLE secretary_notices(id TEXT PRIMARY KEY,project TEXT NOT NULL,task TEXT,kind TEXT NOT NULL,status TEXT NOT NULL,attempts INTEGER NOT NULL,next INTEGER NOT NULL,record TEXT NOT NULL,priority INTEGER NOT NULL,created INTEGER NOT NULL,error TEXT NOT NULL DEFAULT '')");
      sql.execute(
          "CREATE INDEX secretary_notice_due ON secretary_notices(status,priority,next,id)");
      sql.execute("CREATE INDEX secretary_notice_history ON secretary_notices(project,next,id)");
      sql.execute(
          "CREATE INDEX secretary_notice_cooldown ON secretary_notices(project,task,kind,created)");
      sql.execute(
          "CREATE TABLE secretary_dependencies(project TEXT NOT NULL,id TEXT NOT NULL,state TEXT NOT NULL,revision INTEGER NOT NULL,PRIMARY KEY(project,id))");
    }
  }

  static void verify(Connection db) throws SQLException {
    try (var sql = db.createStatement()) {
      sql.executeQuery("SELECT project,record,configured FROM secretary_settings LIMIT 0").close();
      sql.executeQuery("SELECT key,value FROM secretary_followup_state LIMIT 0").close();
      sql.executeQuery(
              "SELECT id,project,task,kind,status,attempts,next,record,priority,created,error FROM secretary_notices LIMIT 0")
          .close();
      sql.executeQuery("SELECT project,id,state,revision FROM secretary_dependencies LIMIT 0")
          .close();
    }
  }
}
