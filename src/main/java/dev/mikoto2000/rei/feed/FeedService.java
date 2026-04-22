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

  public List<Feed> listActive() {
    return jdbcClient.sql("""
        SELECT id, url, title, site_url, description, display_name, enabled, created_at, updated_at, last_fetched_at
        FROM feeds
        WHERE enabled = 1
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

  public Feed findById(long id) {
    return jdbcClient.sql("""
        SELECT id, url, title, site_url, description, display_name, enabled, created_at, updated_at, last_fetched_at
        FROM feeds
        WHERE id = ?
        """)
        .param(id)
        .query(this::mapFeed)
        .single();
  }

  public Feed updateFetchedMetadata(long id, String title, String siteUrl, String description, OffsetDateTime fetchedAt) {
    Feed current = findById(id);
    jdbcClient.sql("""
        UPDATE feeds
        SET title = ?, site_url = ?, description = ?, updated_at = ?, last_fetched_at = ?
        WHERE id = ?
        """)
        .params(
            title == null || title.isBlank() ? current.title() : title,
            siteUrl == null || siteUrl.isBlank() ? current.siteUrl() : siteUrl,
            description == null || description.isBlank() ? current.description() : description,
            fetchedAt.toString(),
            fetchedAt.toString(),
            id)
        .update();
    return findById(id);
  }

  public int saveFetchedItems(long feedId, List<FetchedFeedItem> items, OffsetDateTime fetchedAt) {
    int added = 0;
    for (FetchedFeedItem item : items) {
      String dedupeKey = dedupeKey(item);
      if (dedupeKey == null) {
        continue;
      }
      OffsetDateTime now = fetchedAt == null ? OffsetDateTime.now(ZoneOffset.UTC) : fetchedAt;
      int updated = jdbcClient.sql("""
          INSERT OR IGNORE INTO feed_items
            (feed_id, title, url, published_at, fetched_at, created_at, updated_at, dedupe_key)
          VALUES (?, ?, ?, ?, ?, ?, ?, ?)
          """)
          .params(
              feedId,
              item.title(),
              item.url(),
              item.publishedAt() == null ? null : item.publishedAt().toString(),
              now.toString(),
              now.toString(),
              now.toString(),
              dedupeKey)
          .update();
      added += updated;
    }
    return added;
  }

  public List<FeedItem> listItemsForFeed(long feedId) {
    return jdbcClient.sql("""
        SELECT id, feed_id, title, url, published_at, fetched_at, created_at, updated_at
        FROM feed_items
        WHERE feed_id = ?
        ORDER BY published_at DESC, id DESC
        """)
        .param(feedId)
        .query(this::mapFeedItem)
        .list();
  }

  public void recordFetchFailure(long feedId, OffsetDateTime failedAt, String errorMessage, Integer httpStatus) {
    jdbcClient.sql("""
        INSERT INTO feed_fetch_failures (feed_id, failed_at, error_message, http_status)
        VALUES (?, ?, ?, ?)
        """)
        .params(feedId, failedAt.toString(), errorMessage, httpStatus)
        .update();
  }

  public List<FeedFetchFailure> listFailures(long feedId) {
    return jdbcClient.sql("""
        SELECT id, feed_id, failed_at, error_message, http_status
        FROM feed_fetch_failures
        WHERE feed_id = ?
        ORDER BY failed_at DESC, id DESC
        """)
        .param(feedId)
        .query(this::mapFeedFetchFailure)
        .list();
  }

  public List<FeedBriefingItem> listBriefingItems(OffsetDateTime from, OffsetDateTime to, int maxItems) {
    return jdbcClient.sql("""
        SELECT i.id, i.title, i.url, i.published_at,
               COALESCE(NULLIF(f.display_name, ''), NULLIF(f.title, ''), f.url) AS feed_name
        FROM feed_items i
        JOIN feeds f ON f.id = i.feed_id
        WHERE f.enabled = 1
          AND i.published_at IS NOT NULL
          AND i.published_at >= ?
          AND i.published_at <= ?
        ORDER BY i.published_at DESC, i.id DESC
        LIMIT ?
        """)
        .params(from.toString(), to.toString(), maxItems)
        .query(this::mapFeedBriefingItem)
        .list();
  }

  public FeedBriefingItem findBriefingItem(long itemId) {
    return jdbcClient.sql("""
        SELECT i.id, i.title, i.url, i.published_at,
               COALESCE(NULLIF(f.display_name, ''), NULLIF(f.title, ''), f.url) AS feed_name
        FROM feed_items i
        JOIN feeds f ON f.id = i.feed_id
        WHERE i.id = ?
        """)
        .param(itemId)
        .query(this::mapFeedBriefingItem)
        .single();
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

  private FeedItem mapFeedItem(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
    String publishedAt = rs.getString("published_at");
    return new FeedItem(
        rs.getLong("id"),
        rs.getLong("feed_id"),
        rs.getString("title"),
        rs.getString("url"),
        publishedAt == null ? null : OffsetDateTime.parse(publishedAt),
        OffsetDateTime.parse(rs.getString("fetched_at")),
        OffsetDateTime.parse(rs.getString("created_at")),
        OffsetDateTime.parse(rs.getString("updated_at")));
  }

  private FeedFetchFailure mapFeedFetchFailure(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
    int httpStatus = rs.getInt("http_status");
    return new FeedFetchFailure(
        rs.getLong("id"),
        rs.getLong("feed_id"),
        OffsetDateTime.parse(rs.getString("failed_at")),
        rs.getString("error_message"),
        rs.wasNull() ? null : httpStatus);
  }

  private FeedBriefingItem mapFeedBriefingItem(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
    return new FeedBriefingItem(
        rs.getLong("id"),
        rs.getString("title"),
        rs.getString("url"),
        OffsetDateTime.parse(rs.getString("published_at")),
        rs.getString("feed_name"));
  }

  private String dedupeKey(FetchedFeedItem item) {
    if (item.url() != null && !item.url().isBlank()) {
      return "url:" + item.url().trim();
    }
    if (item.title() != null && !item.title().isBlank() && item.publishedAt() != null) {
      return "title-published:" + item.title().trim() + "|" + item.publishedAt();
    }
    return null;
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
