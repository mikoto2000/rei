package dev.mikoto2000.rei.storage;

import java.sql.Connection;
import java.sql.SQLException;

/** データ保存の共通処理に必要なレビュー対象範囲移行を扱う。 */
final class ReviewCoverageMigration {
  /** 指定された内容を、現在の対象へ反映する。 */
  static void apply(Connection db) throws SQLException {
    try (var sql = db.createStatement()) {
      sql.execute(
          "CREATE TABLE review_jobs(id TEXT PRIMARY KEY,project TEXT NOT NULL,conversation TEXT NOT NULL,updated TEXT NOT NULL,record TEXT NOT NULL)");
      sql.execute("CREATE INDEX review_jobs_scope ON review_jobs(project,conversation,updated,id)");
    }
  }

  /** 要求された条件を満たすことを確認する。 */
  static void verify(Connection db) throws SQLException {
    try (var sql = db.createStatement()) {
      sql.executeQuery("SELECT id,project,conversation,updated,record FROM review_jobs LIMIT 0")
          .close();
    }
  }
}
