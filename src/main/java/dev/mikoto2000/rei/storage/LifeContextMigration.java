package dev.mikoto2000.rei.storage;

import java.sql.Connection;
import java.sql.SQLException;

/** 既存データを保持した生活制約設定の追加。 */
final class LifeContextMigration {
  static void apply(Connection db) throws SQLException {
    try (var q = db.createStatement()) {
      q.execute(
          "CREATE TABLE life_context(id INTEGER PRIMARY KEY CHECK(id=1),revision INTEGER NOT NULL,record TEXT NOT NULL)");
    }
  }

  static void verify(Connection db) throws SQLException {
    try (var q = db.createStatement();
        var r = q.executeQuery("SELECT id,revision,record FROM life_context LIMIT 0")) {
      /* schema only */
    }
  }
}
