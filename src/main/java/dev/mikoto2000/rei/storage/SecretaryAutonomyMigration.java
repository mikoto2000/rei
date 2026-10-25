package dev.mikoto2000.rei.storage;

import java.sql.Connection;
import java.sql.SQLException;

final class SecretaryAutonomyMigration {
  static void apply(Connection db) throws SQLException {
    try (var sql = db.createStatement()) {
      sql.execute(
          "CREATE TABLE secretary_autonomy_settings(project TEXT PRIMARY KEY,record TEXT NOT NULL,configured INTEGER NOT NULL)");
      sql.execute(
          "CREATE TABLE secretary_jobs(id TEXT PRIMARY KEY,project TEXT NOT NULL,task TEXT NOT NULL,receipt TEXT NOT NULL,spec TEXT NOT NULL,state TEXT NOT NULL,revision INTEGER NOT NULL,run TEXT,attempts INTEGER NOT NULL,calls INTEGER NOT NULL,allocated INTEGER NOT NULL,deadline INTEGER NOT NULL,next INTEGER NOT NULL,result TEXT,reason TEXT NOT NULL,permit TEXT,authorization TEXT,created INTEGER NOT NULL,updated INTEGER NOT NULL,UNIQUE(project,receipt))");
      sql.execute("CREATE INDEX secretary_jobs_due ON secretary_jobs(state,next,id)");
      sql.execute("CREATE INDEX secretary_jobs_project ON secretary_jobs(project,created DESC,id)");
      sql.execute("CREATE INDEX secretary_jobs_task ON secretary_jobs(project,task,state)");
      sql.execute(
          "CREATE TABLE secretary_job_history(sequence INTEGER PRIMARY KEY AUTOINCREMENT,project TEXT NOT NULL,job TEXT NOT NULL,state TEXT NOT NULL,reason TEXT NOT NULL,revision INTEGER NOT NULL,at INTEGER NOT NULL)");
      sql.execute(
          "CREATE INDEX secretary_job_history_owner ON secretary_job_history(project,job,sequence)");
      sql.execute(
          "CREATE TABLE secretary_model_budget(project TEXT NOT NULL,day TEXT NOT NULL,calls INTEGER NOT NULL,PRIMARY KEY(project,day))");
    }
  }

  static void verify(Connection db) throws SQLException {
    try (var sql = db.createStatement()) {
      sql.executeQuery("SELECT project,record,configured FROM secretary_autonomy_settings LIMIT 0")
          .close();
      sql.executeQuery(
              "SELECT id,project,task,receipt,spec,state,revision,run,attempts,calls,allocated,deadline,next,result,reason,permit,authorization,created,updated FROM secretary_jobs LIMIT 0")
          .close();
      sql.executeQuery(
              "SELECT sequence,project,job,state,reason,revision,at FROM secretary_job_history LIMIT 0")
          .close();
      sql.executeQuery("SELECT project,day,calls FROM secretary_model_budget LIMIT 0").close();
    }
  }
}
