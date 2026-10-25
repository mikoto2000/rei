package dev.mikoto2000.rei.storage;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

/**
 * SQLiteイベント続きの取得位置の記録を保持し、後続の処理で参照できるようにする。
 *
 * <p>Cursor units are project sequences after startup conversion, never byte offsets.
 */
public final class SqliteEventCursorStore {
  /**
   * データ保存の共通処理で受け渡す続きの取得位置を表す。
   *
   * @param sequence 発生順序
   * @param generation 生成
   */
  public record Cursor(long sequence, long generation) {}

  /** データベース。 */
  private final StorageDatabase database;

  /** 指定された値や利用先を使って、SqliteEventCursorStoreを初期化する。 */
  public SqliteEventCursorStore(Path root) {
    database = new StorageDatabase(root);
  }

  /** データ保存の共通処理で用いる対象範囲を返す。 */
  private static String scope(String project) {
    return UUID.fromString(project).toString();
  }

  /** 指定された対象の情報を、呼び出し元が参照できる形で返す。 */
  public Cursor get(String project) {
    return database.read(db -> get(db, scope(project)));
  }

  /** 指定された対象の情報を、呼び出し元が参照できる形で返す。 */
  private static Cursor get(Connection db, String project) throws SQLException {
    try (var query =
        db.prepareStatement(
            "SELECT sequence,generation FROM event_cursors WHERE project_id=? AND converted=1")) {
      query.setString(1, project);
      try (var rows = query.executeQuery()) {
        return rows.next() ? new Cursor(rows.getLong(1), rows.getLong(2)) : new Cursor(0, 0);
      }
    }
  }

  /** 新たな処理を開始できるよう、SQLiteイベント続きの取得位置保存先を初期状態へ戻す。 */
  public void reset(String project) {
    String scope = scope(project);
    database.transaction(
        db -> {
          long generation;
          try (var query =
              db.prepareStatement("SELECT generation FROM event_cursors WHERE project_id=?")) {
            query.setString(1, scope);
            try (var rows = query.executeQuery()) {
              generation = rows.next() ? Math.addExact(rows.getLong(1), 1) : 0;
            }
          }
          try (var update =
              db.prepareStatement(
                  "INSERT INTO event_cursors(project_id,sequence,generation,converted) VALUES(?,0,?,1) ON CONFLICT(project_id) DO UPDATE SET sequence=0,generation=excluded.generation,converted=1")) {
            update.setString(1, scope);
            update.setLong(2, generation);
            update.executeUpdate();
          }
          return null;
        });
  }

  /** データ保存の共通処理において、比較および集合に該当するか判定する。 */
  public boolean compareAndSet(String project, Cursor expected, long next) {
    if (next < expected.sequence() || next < 0)
      throw new IllegalArgumentException("Cursor cannot move backwards without a new generation");
    if (next == expected.sequence()) {
      // 同じ位置でもCASの成立条件はDBで一度に確認する。未作成や世代違いは成功にしない。
      return database.read(
          db -> {
            try (var query =
                db.prepareStatement(
                    "SELECT 1 FROM event_cursors WHERE project_id=? AND sequence=? AND generation=? AND converted=1 AND ?<=COALESCE((SELECT sequence FROM event_sequences WHERE project_id=?),0)")) {
              String scope = scope(project);
              query.setString(1, scope);
              query.setLong(2, expected.sequence());
              query.setLong(3, expected.generation());
              query.setLong(4, next);
              query.setString(5, scope);
              try (var rows = query.executeQuery()) {
                return rows.next();
              }
            }
          });
    }
    return database.transaction(
        db -> {
          try (var update =
              db.prepareStatement(
                  "UPDATE event_cursors SET sequence=? WHERE project_id=? AND sequence=? AND generation=? AND converted=1 AND ?<=COALESCE((SELECT sequence FROM event_sequences WHERE project_id=?),0)")) {
            String scope = scope(project);
            update.setLong(1, next);
            update.setString(2, scope);
            update.setLong(3, expected.sequence());
            update.setLong(4, expected.generation());
            update.setLong(5, next);
            update.setString(6, scope);
            return update.executeUpdate() == 1;
          }
        });
  }
}
