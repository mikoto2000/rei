package dev.mikoto2000.rei.storage;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;

/** Durable claim history; UTF-16 lengths retain the existing review coordinate contract. */
final class DurableWorkMigration {
  static void apply(Connection db) throws SQLException {
    try (var sql = db.createStatement()) {
      sql.execute("ALTER TABLE durable_tasks ADD COLUMN current_run TEXT");
      sql.execute("UPDATE durable_tasks SET current_run=(SELECT run FROM durable_attempts WHERE task=durable_tasks.id ORDER BY started DESC,run DESC LIMIT 1)");
      sql.execute("ALTER TABLE durable_files ADD COLUMN chars INTEGER NOT NULL DEFAULT 0");
      sql.execute("ALTER TABLE durable_work ADD COLUMN active_attempt TEXT");
      sql.execute("ALTER TABLE durable_work ADD COLUMN result_id TEXT");
      sql.execute("ALTER TABLE durable_work ADD COLUMN last_step TEXT NOT NULL DEFAULT 'REGISTERED'");
      sql.execute("CREATE TABLE durable_item_attempts(id TEXT PRIMARY KEY,work TEXT NOT NULL,run TEXT NOT NULL,started INTEGER NOT NULL,state TEXT NOT NULL,delivered_hash TEXT NOT NULL DEFAULT '',failure TEXT NOT NULL DEFAULT '')");
      sql.execute("CREATE INDEX durable_item_attempt_work ON durable_item_attempts(work,started,id)");
      sql.execute("CREATE TABLE durable_results(work TEXT PRIMARY KEY,task TEXT NOT NULL,snapshot TEXT NOT NULL,path TEXT NOT NULL,start INTEGER NOT NULL,end INTEGER NOT NULL,version TEXT NOT NULL,criterion TEXT NOT NULL,attempt TEXT NOT NULL,body TEXT NOT NULL,sha256 TEXT NOT NULL,created INTEGER NOT NULL)");
      sql.execute("CREATE INDEX durable_result_task ON durable_results(task,work)");
      sql.execute("CREATE TABLE durable_findings(work TEXT NOT NULL,id TEXT NOT NULL,severity TEXT NOT NULL,start INTEGER NOT NULL,end INTEGER NOT NULL,evidence TEXT NOT NULL,assessment TEXT NOT NULL,PRIMARY KEY(work,id))");
    }
    String snapshot = "", path = "";
    while (true) {
      var lengths = new ArrayList<Object[]>();
      try (var query = db.prepareStatement("SELECT snapshot,path,text FROM durable_files WHERE snapshot>? OR (snapshot=? AND path>?) ORDER BY snapshot,path LIMIT 1000")) {
        query.setString(1, snapshot);
        query.setString(2, snapshot);
        query.setString(3, path);
        try (var rows = query.executeQuery()) {
          while (rows.next()) {
            String text = rows.getString(3);
            lengths.add(new Object[] {rows.getString(1), rows.getString(2), text == null ? 0 : text.length()});
          }
        }
      }
      for (var entry : lengths) {
        try (var update = db.prepareStatement("UPDATE durable_files SET chars=? WHERE snapshot=? AND path=?")) {
          update.setInt(1, (Integer) entry[2]);
          update.setString(2, (String) entry[0]);
          update.setString(3, (String) entry[1]);
          update.executeUpdate();
        }
        snapshot = (String) entry[0];
        path = (String) entry[1];
      }
      if (lengths.size() < 1000) break;
    }
  }

  static void verify(Connection db) throws SQLException {
    try (var sql = db.createStatement()) {
      sql.executeQuery("SELECT current_run FROM durable_tasks LIMIT 0").close();
      sql.executeQuery("SELECT chars FROM durable_files LIMIT 0").close();
      sql.executeQuery("SELECT active_attempt,result_id,last_step FROM durable_work LIMIT 0").close();
      sql.executeQuery("SELECT id,work,run,started,state,delivered_hash,failure FROM durable_item_attempts LIMIT 0").close();
      sql.executeQuery("SELECT work,task,snapshot,path,start,end,version,criterion,attempt,body,sha256,created FROM durable_results LIMIT 0").close();
      sql.executeQuery("SELECT work,id,severity,start,end,evidence,assessment FROM durable_findings LIMIT 0").close();
    }
  }
}
