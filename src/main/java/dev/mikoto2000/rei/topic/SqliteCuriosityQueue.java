package dev.mikoto2000.rei.topic;

import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;

import javax.sql.DataSource;

import org.springframework.jdbc.core.simple.JdbcClient;

public class SqliteCuriosityQueue implements CuriosityQueue {
  private final JdbcClient jdbcClient;

  public SqliteCuriosityQueue(DataSource dataSource) {
    this.jdbcClient = JdbcClient.create(dataSource);
    initializeSchema();
  }

  @Override
  public synchronized void add(CuriosityItem item) {
    jdbcClient.sql("""
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

  @Override
  public synchronized List<CuriosityItem> findCandidates(CuriosityQuery query) {
    int limit = Math.max(0, query.limit());
    if (limit == 0) return List.of();
    Instant now = query.now();
    jdbcClient.sql("""
        UPDATE curiosity_items
        SET status = 'EXPIRED'
        WHERE status = 'PENDING' AND expires_at IS NOT NULL AND expires_at <= ?
        """)
        .param(toDbTime(now))
        .update();
    return jdbcClient.sql("""
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

  @Override
  public synchronized void markUsed(String id) {
    updateStatus(id, CuriosityStatus.USED);
  }

  @Override
  public synchronized void dismiss(String id) {
    updateStatus(id, CuriosityStatus.DISMISSED);
  }

  private void updateStatus(String id, CuriosityStatus status) {
    if (id == null || id.isBlank()) return;
    jdbcClient.sql("UPDATE curiosity_items SET status = ? WHERE id = ?")
        .params(status.name(), id)
        .update();
  }

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

  private void initializeSchema() {
    jdbcClient.sql("""
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
        """).update();
    addColumnIfMissing("curiosity_items", "normalized_question", "TEXT");
    jdbcClient.sql("""
        CREATE UNIQUE INDEX IF NOT EXISTS idx_curiosity_items_normalized_question
        ON curiosity_items(normalized_question)
        """).update();
    jdbcClient.sql("""
        CREATE INDEX IF NOT EXISTS idx_curiosity_items_status_priority
        ON curiosity_items(status, priority DESC, created_at ASC)
        """).update();
  }

  private void addColumnIfMissing(String table, String column, String type) {
    boolean exists = jdbcClient.sql("PRAGMA table_info(" + table + ")")
        .query((rs, rowNum) -> rs.getString("name"))
        .list()
        .contains(column);
    if (!exists) {
      jdbcClient.sql("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type).update();
    }
  }

  private String toDbTime(Instant value) {
    return value == null ? null : value.toString();
  }

  private Instant fromDbTime(String value) {
    return value == null ? null : Instant.parse(value);
  }
}
