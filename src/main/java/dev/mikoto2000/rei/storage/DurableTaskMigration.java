package dev.mikoto2000.rei.storage;

import java.sql.Connection;
import java.sql.SQLException;

/** Additive durable target inventory; existing review and checkpoint rows remain intact. */
final class DurableTaskMigration {
  static void apply(Connection db) throws SQLException {
    try (var sql = db.createStatement()) {
      sql.execute("CREATE TABLE durable_tasks(id TEXT PRIMARY KEY,project TEXT NOT NULL,session TEXT NOT NULL,root TEXT NOT NULL,goal TEXT,snapshot TEXT NOT NULL UNIQUE,state TEXT NOT NULL,revision INTEGER NOT NULL DEFAULT 0)");
      sql.execute("CREATE INDEX durable_task_owner ON durable_tasks(project,session,root,id)");
      sql.execute("CREATE INDEX durable_task_goal ON durable_tasks(project,goal)");
      sql.execute("CREATE TABLE durable_attempts(task TEXT NOT NULL,run TEXT NOT NULL,started INTEGER NOT NULL,state TEXT NOT NULL,PRIMARY KEY(task,run))");
      sql.execute("CREATE INDEX durable_attempt_run ON durable_attempts(run,task)");
      sql.execute("CREATE TABLE durable_snapshots(id TEXT PRIMARY KEY,task TEXT NOT NULL UNIQUE,mode TEXT NOT NULL,source_ref TEXT NOT NULL,state TEXT NOT NULL,reason TEXT NOT NULL,excludes TEXT NOT NULL,max_files INTEGER NOT NULL,max_bytes INTEGER NOT NULL,files INTEGER NOT NULL DEFAULT 0,excluded INTEGER NOT NULL DEFAULT 0,errors INTEGER NOT NULL DEFAULT 0,bytes INTEGER NOT NULL DEFAULT 0)");
      sql.execute("CREATE TABLE durable_files(snapshot TEXT NOT NULL,path TEXT NOT NULL,version TEXT NOT NULL,content BLOB,text TEXT,bytes INTEGER NOT NULL,reason TEXT NOT NULL,error TEXT NOT NULL,PRIMARY KEY(snapshot,path))");
      sql.execute("CREATE TABLE durable_directories(task TEXT NOT NULL,path TEXT NOT NULL,cursor TEXT NOT NULL DEFAULT '',state TEXT NOT NULL DEFAULT 'PENDING',stamp TEXT NOT NULL DEFAULT '',PRIMARY KEY(task,path))");
      sql.execute("CREATE INDEX durable_directory_pending ON durable_directories(task,state,path)");
      sql.execute("CREATE TABLE durable_work(id TEXT PRIMARY KEY,task TEXT NOT NULL,snapshot TEXT NOT NULL,path TEXT NOT NULL,start INTEGER NOT NULL,end INTEGER NOT NULL,version TEXT NOT NULL,kind TEXT NOT NULL,criterion TEXT NOT NULL,state TEXT NOT NULL,required INTEGER NOT NULL,UNIQUE(task,snapshot,path,start,end,kind,criterion))");
      sql.execute("CREATE INDEX durable_work_pending ON durable_work(task,state,id)");
    }
  }

  static void verify(Connection db) throws SQLException {
    try (var sql = db.createStatement()) {
      sql.executeQuery("SELECT id,project,session,root,goal,snapshot,state,revision FROM durable_tasks LIMIT 0").close();
      sql.executeQuery("SELECT task,run,started,state FROM durable_attempts LIMIT 0").close();
      sql.executeQuery("SELECT id,task,mode,source_ref,state,reason,excludes,max_files,max_bytes,files,excluded,errors,bytes FROM durable_snapshots LIMIT 0").close();
      sql.executeQuery("SELECT snapshot,path,version,content,text,bytes,reason,error FROM durable_files LIMIT 0").close();
      sql.executeQuery("SELECT task,path,cursor,state,stamp FROM durable_directories LIMIT 0").close();
      sql.executeQuery("SELECT id,task,snapshot,path,start,end,version,kind,criterion,state,required FROM durable_work LIMIT 0").close();
    }
  }
}
