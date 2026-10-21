package dev.mikoto2000.rei.core;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 共通の実行基盤に必要な永続化済み受付記録利用権を扱う。
 *
 * <p>Process identity and in-process liveness for the existing review, repair and single-file
 * receipt tables.
 */
final class PersistedReceiptLease {
  /** 活動中。 */
  private static final Set<String> ACTIVE = java.util.concurrent.ConcurrentHashMap.newKeySet();

  /** データベースへのアクセス。 */
  private final JdbcClient db;

  /** 保存テーブル／識別範囲／活動中状態。 */
  private final String table, namespace, activeStatus;

  /** 期限や記録日時の判定に使う時刻の取得元。 */
  private final Clock clock;

  /** 指定された値や利用先を使って、PersistedReceiptLeaseを初期化する。 */
  PersistedReceiptLease(DataSource source, String table, Clock clock) {
    if (!Set.of("patch_requirement_reviews", "diagnosed_repairs", "text_change_sets")
        .contains(table)) throw new IllegalArgumentException("Receipt table required");
    this.table = table;
    this.activeStatus = table.equals("text_change_sets") ? "APPLYING" : "STARTED";
    this.clock = clock;
    db = JdbcClient.create(source);
    try (var connection = source.getConnection()) {
      namespace = connection.getMetaData().getURL() + ":" + table + ":";
    } catch (java.sql.SQLException failure) {
      throw new IllegalStateException("Receipt storage identity unavailable", failure);
    }
    var columns =
        db.sql("PRAGMA table_info(" + table + ")").query((rs, n) -> rs.getString("name")).list();
    for (String column : List.of("pid INTEGER", "process_start TEXT", "heartbeat INTEGER"))
      if (!columns.contains(column.split(" ")[0]))
        db.sql("ALTER TABLE " + table + " ADD COLUMN " + column).update();
    reconcile(null);
  }

  /** 共通の実行基盤で用いるactivateを返す。 */
  AutoCloseableLease activate(String id) {
    String key = namespace + id;
    if (!ACTIVE.add(key)) throw new IllegalStateException("Receipt operation already active");
    try {
      if (db.sql(
                  "UPDATE "
                      + table
                      + " SET pid=?,process_start=?,heartbeat=? WHERE id=? AND status='"
                      + activeStatus
                      + "'")
              .params(ProcessHandle.current().pid(), start(), clock.millis(), id)
              .update()
          != 1) throw new IllegalStateException("Receipt claim lost; inspect without replay");
      return new AutoCloseableLease(key);
    } catch (RuntimeException failure) {
      ACTIVE.remove(key);
      throw failure;
    }
  }

  /** 共通の実行基盤に必要な自動Closeable利用権を扱う。 */
  final class AutoCloseableLease implements AutoCloseable {
    /** 照合キー。 */
    private final String key;

    /** 指定された値や利用先を使って、AutoCloseableLeaseを初期化する。 */
    AutoCloseableLease(String key) {
      this.key = key;
    }

    /** 共通の実行基盤で利用した資源や登録を解放し、利用を終了する。 */
    public void close() {
      ACTIVE.remove(key);
    }
  }

  /** 共通の実行基盤に関する生存確認を処理する。 */
  void heartbeat(String id) {
    if (db.sql(
                "UPDATE "
                    + table
                    + " SET heartbeat=? WHERE id=? AND status='"
                    + activeStatus
                    + "' AND pid=? AND process_start=?")
            .params(clock.millis(), id, ProcessHandle.current().pid(), start())
            .update()
        != 1) throw new IllegalStateException("Receipt execution lease lost");
  }

  /** 現在の状態と記録を照合し、差異に応じて管理状態を整える。 */
  void reconcile(String id) {
    /**
     * 共通の実行基盤で受け渡す行を表す。
     *
     * @param id 識別子
     * @param pid プロセス識別子
     * @param start 開始
     */
    record Row(String id, long pid, String start) {}
    String query =
        "SELECT id,COALESCE(pid,0) AS pid,COALESCE(process_start,'') AS process_start FROM "
            + table
            + " WHERE status='"
            + activeStatus
            + "'";
    var statement = db.sql(query + (id == null ? " LIMIT 4096" : " AND id=?"));
    if (id != null) statement = statement.param(id);
    for (var row :
        statement
            .query(
                (rs, n) ->
                    new Row(rs.getString("id"), rs.getLong("pid"), rs.getString("process_start")))
            .list()) {
      boolean alive = false;
      try {
        alive =
            row.pid() == ProcessHandle.current().pid()
                ? start().equals(row.start()) && ACTIVE.contains(namespace + row.id())
                : ProcessHandle.of(row.pid())
                    .filter(ProcessHandle::isAlive)
                    .flatMap(process -> process.info().startInstant())
                    .map(instant -> instant.toString().equals(row.start()))
                    .orElse(false);
      } catch (RuntimeException unavailable) {
        alive = false;
      }
      if (!alive)
        db.sql(
                "UPDATE "
                    + table
                    + " SET status='UNKNOWN',heartbeat=? WHERE id=? AND status='"
                    + activeStatus
                    + "' AND COALESCE(pid,0)=? AND COALESCE(process_start,'')=?")
            .params(clock.millis(), row.id(), row.pid(), row.start())
            .update();
    }
  }

  /** 要求された処理を開始する。 */
  private static String start() {
    return ProcessHandle.current().info().startInstant().map(Instant::toString).orElse("unknown");
  }
}
