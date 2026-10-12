package dev.mikoto2000.rei.storage;

import java.sql.*;

final class DotStorageMigration {
  static void apply(Connection db) throws SQLException {
    try (var s = db.createStatement()) {
      s.execute(
          "CREATE TABLE dot_requests(id TEXT PRIMARY KEY,project TEXT NOT NULL,session TEXT,run"
              + " TEXT UNIQUE,root TEXT NOT NULL,task TEXT,publication TEXT,status TEXT NOT"
              + " NULL,created INTEGER NOT NULL,expires INTEGER NOT NULL,claim TEXT,lease"
              + " INTEGER,claim_json TEXT,dependency TEXT,published INTEGER NOT NULL DEFAULT"
              + " 1,authority TEXT,validation TEXT NOT NULL DEFAULT 'NOT_RECEIVED',review_note"
              + " TEXT,creation_fingerprint TEXT NOT NULL)");
      s.execute("CREATE INDEX dot_requests_poll ON dot_requests(status,id)");
      s.execute(
          "CREATE TABLE dot_receipts(request TEXT PRIMARY KEY,submission TEXT NOT NULL"
              + " UNIQUE,fingerprint TEXT NOT NULL,result TEXT,receipt TEXT NOT NULL,received"
              + " INTEGER NOT NULL)");
      s.execute(
          "CREATE TABLE dot_resume_intents(request TEXT PRIMARY KEY,status TEXT NOT NULL,run"
              + " TEXT,schedule TEXT,revision INTEGER,updated INTEGER NOT NULL)");
      s.execute(
          "CREATE TABLE dot_audit(id TEXT PRIMARY KEY,request TEXT,project TEXT,actor TEXT NOT"
              + " NULL,operation TEXT NOT NULL,outcome TEXT NOT NULL,created INTEGER NOT NULL)");
    }
  }

  static void verify(Connection db) throws SQLException {
    try (var s = db.createStatement()) {
      s.executeQuery(
              "SELECT"
                  + " id,project,session,run,root,task,publication,status,created,expires,claim,lease,claim_json,dependency,published,authority,validation,review_note,creation_fingerprint"
                  + " FROM dot_requests LIMIT 0")
          .close();
      s.executeQuery(
              "SELECT request,submission,fingerprint,result,receipt,received FROM dot_receipts"
                  + " LIMIT 0")
          .close();
      s.executeQuery(
              "SELECT request,status,run,schedule,revision,updated FROM dot_resume_intents LIMIT 0")
          .close();
      s.executeQuery(
              "SELECT id,request,project,actor,operation,outcome,created FROM dot_audit LIMIT 0")
          .close();
    }
  }
}
