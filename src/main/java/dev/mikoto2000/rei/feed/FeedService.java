package dev.mikoto2000.rei.feed;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import javax.sql.DataSource;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class FeedService {

  private final JdbcClient jdbcClient;

  public FeedService(DataSource dataSource) {
    this.jdbcClient = JdbcClient.create(dataSource);
    initializeSchema(dataSource);
  }

  public Feed add(String url, String displayName) {
    if (existsByUrl(url)) {
      throw new IllegalArgumentException("同じフィード URL は登録できません");
    }

    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    Long id = jdbcClient.sql("""
        INSERT INTO feeds (url, title, site_url, description, display_name, enabled, created_at, updated_at, last_fetched_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        RETURNING id
        """)
        .params(url, null, null, null, displayName, 1, now.toString(), now.toString(), null)
        .query(Long.class)
        .single();

    return findById(id);
  }

  public List<Feed> list() {
    return jdbcClient.sql("""
        SELECT id, url, title, site_url, description, display_name, enabled, created_at, updated_at, last_fetched_at
        FROM feeds
        ORDER BY id ASC
        """)
        .query(this::mapFeed)
        .list();
  }

  public Feed update(long id, String displayName, Boolean enabled) {
    Feed current = findById(id);
    String resolvedDisplayName = displayName == null || displayName.isBlank() ? current.displayName() : displayName;
    boolean resolvedEnabled = enabled == null ? current.enabled() : enabled;
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

    jdbcClient.sql("""
        UPDATE feeds
        SET display_name = ?, enabled = ?, updated_at = ?
        WHERE id = ?
        """)
        .params(resolvedDisplayName, resolvedEnabled ? 1 : 0, now.toString(), id)
        .update();

    return findById(id);
  }

  public void delete(long id) {
    jdbcClient.sql("DELETE FROM feed_fetch_failures WHERE feed_id = ?")
        .param(id)
        .update();
    jdbcClient.sql("DELETE FROM feed_items WHERE feed_id = ?")
        .param(id)
        .update();
    jdbcClient.sql("DELETE FROM feeds WHERE id = ?")
        .param(id)
        .update();
  }

  private boolean existsByUrl(String url) {
    Long count = jdbcClient.sql("SELECT COUNT(*) FROM feeds WHERE url = ?")
        .param(url)
        .query(Long.class)
        .single();
    return count != null && count > 0;
  }

  private Feed findById(long id) {
    return jdbcClient.sql("""
        SELECT id, url, title, site_url, description, display_name, enabled, created_at, updated_at, last_fetched_at
        FROM feeds
        WHERE id = ?
        """)
        .param(id)
        .query(this::mapFeed)
        .single();
  }

  private Feed mapFeed(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
    String lastFetchedAt = rs.getString("last_fetched_at");

    return new Feed(
        rs.getLong("id"),
        rs.getString("url"),
        rs.getString("title"),
        rs.getString("site_url"),
        rs.getString("description"),
        rs.getString("display_name"),
        rs.getInt("enabled") != 0,
        OffsetDateTime.parse(rs.getString("created_at")),
        OffsetDateTime.parse(rs.getString("updated_at")),
        lastFetchedAt == null ? null : OffsetDateTime.parse(lastFetchedAt));
  }

  private void initializeSchema(DataSource dataSource) {
    try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
      statement.executeUpdate("""
          CREATE TABLE IF NOT EXISTS feeds (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            url TEXT NOT NULL UNIQUE,
            title TEXT,
            site_url TEXT,
            description TEXT,
            display_name TEXT,
            enabled INTEGER NOT NULL,
            created_at TEXT NOT NULL,
            updated_at TEXT NOT NULL,
            last_fetched_at TEXT
          )
          """);
      statement.executeUpdate("""
          CREATE TABLE IF NOT EXISTS feed_items (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            feed_id INTEGER NOT NULL,
            title TEXT NOT NULL,
            url TEXT,
            published_at TEXT,
            fetched_at TEXT NOT NULL,
            created_at TEXT NOT NULL,
            updated_at TEXT NOT NULL,
            dedupe_key TEXT NOT NULL,
            FOREIGN KEY(feed_id) REFERENCES feeds(id) ON DELETE CASCADE
          )
          """);
      statement.executeUpdate("""
          CREATE UNIQUE INDEX IF NOT EXISTS idx_feed_items_dedupe_key
          ON feed_items(dedupe_key)
          """);
      statement.executeUpdate("""
          CREATE TABLE IF NOT EXISTS feed_fetch_failures (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            feed_id INTEGER NOT NULL,
            failed_at TEXT NOT NULL,
            error_message TEXT NOT NULL,
            http_status INTEGER,
            FOREIGN KEY(feed_id) REFERENCES feeds(id) ON DELETE CASCADE
          )
          """);
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException("feeds テーブルの初期化に失敗しました", e);
    }
  }
}
