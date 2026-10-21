package dev.mikoto2000.rei.topic;

import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 話題の管理に必要なSQLite情報への関心実行待ちを扱う。 */
public class SqliteCuriosityQueue implements CuriosityQueue {
  /** データベースへの接続クライアント。 */
  private final JdbcClient jdbcClient;

  /** 指定された値や利用先を使って、SqliteCuriosityQueueを初期化する。 */
  public SqliteCuriosityQueue(DataSource dataSource) {
    this.jdbcClient = JdbcClient.create(dataSource);
    initializeSchema();
  }

  /** 新たな対象を登録し、後から利用できるようにする。 */
  @Override
  public synchronized void add(CuriosityItem item) {
    jdbcClient
        .sql(
            """
        INSERT OR IGNORE INTO curiosity_items
          (id, question, normalized_question, reason, source, priority, created_at, expires_at, status)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """)
        .params(
            item.id(),
            item.question(),
            InMemoryCuriosityQueue.normalize(item.question()),
            item.reason(),
            item.source().name(),
            item.priority(),
            toDbTime(item.createdAt()),
            toDbTime(item.expiresAt()),
            item.status().name())
        .update();
  }

  /** 対象を特定できるよう、候補一覧を探す。 */
  @Override
  public synchronized List<CuriosityItem> findCandidates(CuriosityQuery query) {
    int limit = Math.max(0, query.limit());
    if (limit == 0) return List.of();
    Instant now = query.now();
    jdbcClient
        .sql(
            """
        UPDATE curiosity_items
        SET status = 'EXPIRED'
        WHERE status = 'PENDING' AND expires_at IS NOT NULL AND expires_at <= ?
        """)
        .param(toDbTime(now))
        .update();
    return jdbcClient
        .sql(
            """
        SELECT id, question, reason, source, priority, created_at, expires_at, status
        FROM curiosity_items
        WHERE status = 'PENDING'
          AND (expires_at IS NULL OR expires_at > ?)
        ORDER BY priority DESC, created_at ASC
        LIMIT ?
        """)
        .params(toDbTime(now), limit)
        .query(this::mapItem)
        .list();
  }

  /** 後続の判断に現在の状態を反映するため、使用量の状態を記録する。 */
  @Override
  public synchronized void markUsed(String id) {
    updateStatus(id, CuriosityStatus.USED);
  }

  /** 話題の管理に関するdismissを処理する。 */
  @Override
  public synchronized void dismiss(String id) {
    updateStatus(id, CuriosityStatus.DISMISSED);
  }

  /** 最新の内容を後続の処理に反映するため、状態を更新する。 */
  private void updateStatus(String id, CuriosityStatus status) {
    if (id == null || id.isBlank()) return;
    jdbcClient
        .sql("UPDATE curiosity_items SET status = ? WHERE id = ?")
        .params(status.name(), id)
        .update();
  }

  /** 話題の管理で用いる対応表項目を返す。 */
  private CuriosityItem mapItem(ResultSet rs, int rowNum) throws java.sql.SQLException {
    return new CuriosityItem(
        rs.getString("id"),
        rs.getString("question"),
        rs.getString("reason"),
        TopicSource.valueOf(rs.getString("source")),
        rs.getDouble("priority"),
        fromDbTime(rs.getString("created_at")),
        fromDbTime(rs.getString("expires_at")),
        CuriosityStatus.valueOf(rs.getString("status")));
  }

  /** 話題の管理に関する初期化データ形式を処理する。 */
  private void initializeSchema() {
    jdbcClient
        .sql(
            """
        CREATE TABLE IF NOT EXISTS curiosity_items (
          id TEXT PRIMARY KEY,
          question TEXT NOT NULL,
          normalized_question TEXT NOT NULL UNIQUE,
          reason TEXT NOT NULL,
          source TEXT NOT NULL,
          priority REAL NOT NULL,
          created_at TEXT NOT NULL,
          expires_at TEXT,
          status TEXT NOT NULL
        )
        """)
        .update();
    addColumnIfMissing("curiosity_items", "normalized_question", "TEXT");
    jdbcClient
        .sql(
            """
        CREATE UNIQUE INDEX IF NOT EXISTS idx_curiosity_items_normalized_question
        ON curiosity_items(normalized_question)
        """)
        .update();
    jdbcClient
        .sql(
            """
        CREATE INDEX IF NOT EXISTS idx_curiosity_items_status_priority
        ON curiosity_items(status, priority DESC, created_at ASC)
        """)
        .update();
  }

  /** 新たな対象を管理できるよう、列条件付き未指定を追加する。 */
  private void addColumnIfMissing(String table, String column, String type) {
    boolean exists =
        jdbcClient
            .sql("PRAGMA table_info(" + table + ")")
            .query((rs, rowNum) -> rs.getString("name"))
            .list()
            .contains(column);
    if (!exists) {
      jdbcClient.sql("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type).update();
    }
  }

  /** 利用先へ渡せるよう、データベース時刻の形式に変換する。 */
  private String toDbTime(Instant value) {
    return value == null ? null : value.toString();
  }

  /** 話題の管理で用いる起点データベース時刻を返す。 */
  private Instant fromDbTime(String value) {
    return value == null ? null : Instant.parse(value);
  }
}
