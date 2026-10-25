package dev.mikoto2000.rei.storage;

import java.sql.Connection;
import java.sql.SQLException;

final class SecretaryConnectionsMigration {
  static void apply(Connection db) throws SQLException {
    try (var sql = db.createStatement()) {
      sql.execute(
          "CREATE TABLE secretary_connections(id TEXT PRIMARY KEY,project TEXT NOT NULL,name TEXT NOT NULL,service TEXT NOT NULL,version INTEGER NOT NULL,record TEXT NOT NULL,next INTEGER NOT NULL,state TEXT NOT NULL,updated INTEGER NOT NULL,UNIQUE(project,name))");
      sql.execute("CREATE INDEX secretary_connections_due ON secretary_connections(next,id)");
      sql.execute(
          "CREATE TABLE secretary_external_items(connection TEXT NOT NULL,project TEXT NOT NULL,id TEXT NOT NULL,revision TEXT NOT NULL,record TEXT NOT NULL,task TEXT,updated INTEGER NOT NULL,PRIMARY KEY(connection,id))");
      sql.execute(
          "CREATE INDEX secretary_external_items_owner ON secretary_external_items(project,connection,updated DESC,id)");
      sql.execute(
          "CREATE TABLE secretary_external_events(sequence INTEGER PRIMARY KEY AUTOINCREMENT,connection TEXT NOT NULL,project TEXT NOT NULL,item TEXT NOT NULL,revision TEXT NOT NULL,kind TEXT NOT NULL,at INTEGER NOT NULL,UNIQUE(connection,item,revision))");
      sql.execute(
          "CREATE INDEX secretary_external_events_owner ON secretary_external_events(project,connection,sequence)");
      sql.execute(
          "CREATE TABLE secretary_external_actions(id TEXT PRIMARY KEY,project TEXT NOT NULL,connection TEXT NOT NULL,receipt TEXT NOT NULL,record TEXT NOT NULL,state TEXT NOT NULL,revision INTEGER NOT NULL,next INTEGER NOT NULL,updated INTEGER NOT NULL,UNIQUE(project,receipt))");
      sql.execute(
          "CREATE INDEX secretary_external_actions_due ON secretary_external_actions(state,next,id)");
      sql.execute(
          "CREATE TABLE secretary_external_action_history(sequence INTEGER PRIMARY KEY AUTOINCREMENT,project TEXT NOT NULL,action TEXT NOT NULL,state TEXT NOT NULL,reason TEXT NOT NULL,at INTEGER NOT NULL)");
      sql.execute(
          "CREATE INDEX secretary_external_action_history_owner ON secretary_external_action_history(project,action,sequence)");
      sql.execute(
          "CREATE TABLE secretary_external_waits(connection TEXT NOT NULL,project TEXT NOT NULL,thread TEXT NOT NULL,task TEXT NOT NULL,sent INTEGER NOT NULL,checked INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(connection,thread,task))");
      sql.execute("CREATE INDEX secretary_jobs_updated ON secretary_jobs(project,updated DESC,id)");
    }
  }

  static void verify(Connection db) throws SQLException {
    try (var sql = db.createStatement()) {
      sql.executeQuery(
              "SELECT id,project,name,service,version,record,next,state,updated FROM secretary_connections LIMIT 0")
          .close();
      sql.executeQuery(
              "SELECT connection,project,id,revision,record,task,updated FROM secretary_external_items LIMIT 0")
          .close();
      sql.executeQuery(
              "SELECT sequence,connection,project,item,revision,kind,at FROM secretary_external_events LIMIT 0")
          .close();
      sql.executeQuery(
              "SELECT id,project,connection,receipt,record,state,revision,next,updated FROM secretary_external_actions LIMIT 0")
          .close();
      sql.executeQuery(
              "SELECT sequence,project,action,state,reason,at FROM secretary_external_action_history LIMIT 0")
          .close();
      sql.executeQuery(
              "SELECT connection,project,thread,task,sent,checked FROM secretary_external_waits LIMIT 0")
          .close();
    }
  }
}
