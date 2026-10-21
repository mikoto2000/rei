package dev.mikoto2000.rei.feed;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** フィードのユースケースを実現する。 */
@Service
public class FeedService {

  /** データベースへの接続クライアント。 */
  private final JdbcClient jdbcClient;

  /** 指定された値や利用先を使って、FeedServiceを初期化する。 */
  public FeedService(DataSource dataSource) {
    this.jdbcClient = JdbcClient.create(dataSource);
    initializeSchema(dataSource);
  }

  /** 新たな対象を登録し、後から利用できるようにする。 */
  public Feed add(String url, String displayName) {
    FeedUrlSafety.parse(url);
    if (existsByUrl(url)) {
      throw new DuplicateFeedException();
    }

    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    Long id =
        jdbcClient
            .sql(
                """
        INSERT INTO feeds (url, title, site_url, description, display_name, enabled, created_at, updated_at, last_fetched_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        RETURNING id
        """)
            .params(url, null, null, null, displayName, 1, now.toString(), now.toString(), null)
            .query(Long.class)
            .single();

    return findById(id);
  }

  /** 条件に合う対象を一覧にして、呼び出し元が参照できるようにする。 */
  public List<Feed> list() {
    return jdbcClient
        .sql(
            """
        SELECT id, url, title, site_url, description, display_name, enabled, created_at, updated_at, last_fetched_at
        FROM feeds
        ORDER BY id ASC
        """)
        .query(this::mapFeed)
        .list();
  }

  /** 利用可能な対象を確認できるよう、活動中を一覧にする。 */
  public List<Feed> listActive() {
    return jdbcClient
        .sql(
            """
        SELECT id, url, title, site_url, description, display_name, enabled, created_at, updated_at, last_fetched_at
        FROM feeds
        WHERE enabled = 1
        ORDER BY id ASC
        """)
        .query(this::mapFeed)
        .list();
  }

  /** 指定された変更を、管理中の情報へ反映する。 */
  public Feed update(long id, String displayName, Boolean enabled) {
    Feed current = findById(id);
    String resolvedDisplayName =
        displayName == null || displayName.isBlank() ? current.displayName() : displayName;
    boolean resolvedEnabled = enabled == null ? current.enabled() : enabled;
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

    jdbcClient
        .sql(
            """
        UPDATE feeds
        SET display_name = ?, enabled = ?, updated_at = ?
        WHERE id = ?
        """)
        .params(resolvedDisplayName, resolvedEnabled ? 1 : 0, now.toString(), id)
        .update();

    return findById(id);
  }

  /** 対象を特定できるよう、別識別子を探す。 */
  public Feed findById(long id) {
    return jdbcClient
        .sql(
            """
        SELECT id, url, title, site_url, description, display_name, enabled, created_at, updated_at, last_fetched_at
        FROM feeds
        WHERE id = ?
        """)
        .param(id)
        .query(this::mapFeed)
        .single();
  }

  /** 最新の内容を後続の処理に反映するため、取得付随情報を更新する。 */
  public Feed updateFetchedMetadata(
      long id, String title, String siteUrl, String description, OffsetDateTime fetchedAt) {
    Feed current = findById(id);
    jdbcClient
        .sql(
            """
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

  /** 後から参照できるよう、取得項目一覧を保存する。 */
  public int saveFetchedItems(long feedId, List<FetchedFeedItem> items, OffsetDateTime fetchedAt) {
    int added = 0;
    for (FetchedFeedItem item : items) {
      String dedupeKey = dedupeKey(item);
      if (dedupeKey == null) {
        continue;
      }
      OffsetDateTime now = fetchedAt == null ? OffsetDateTime.now(ZoneOffset.UTC) : fetchedAt;
      int updated =
          jdbcClient
              .sql(
                  """
          INSERT OR IGNORE INTO feed_items
            (feed_id, title, url, description, content, published_at, fetched_at, created_at, updated_at, dedupe_key)
          VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
          """)
              .params(
                  feedId,
                  item.title(),
                  item.url(),
                  item.description(),
                  item.content(),
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

  /** 利用可能な対象を確認できるよう、項目一覧対象フィードを一覧にする。 */
  public List<FeedItem> listItemsForFeed(long feedId) {
    return jdbcClient
        .sql(
            """
        SELECT id, feed_id, title, url, description, content, published_at, fetched_at, created_at, updated_at
        FROM feed_items
        WHERE feed_id = ?
        ORDER BY published_at DESC, id DESC
        """)
        .param(feedId)
        .query(this::mapFeedItem)
        .list();
  }

  /** 後から経緯や使用量を確認できるよう、取得失敗情報を記録する。 */
  public void recordFetchFailure(
      long feedId, OffsetDateTime failedAt, String errorMessage, Integer httpStatus) {
    jdbcClient
        .sql(
            """
        INSERT INTO feed_fetch_failures (feed_id, failed_at, error_message, http_status)
        VALUES (?, ?, ?, ?)
        """)
        .params(feedId, failedAt.toString(), errorMessage, httpStatus)
        .update();
  }

  /** 利用可能な対象を確認できるよう、失敗一覧を一覧にする。 */
  public List<FeedFetchFailure> listFailures(long feedId) {
    return jdbcClient
        .sql(
            """
        SELECT id, feed_id, failed_at, error_message, http_status
        FROM feed_fetch_failures
        WHERE feed_id = ?
        ORDER BY failed_at DESC, id DESC
        """)
        .param(feedId)
        .query(this::mapFeedFetchFailure)
        .list();
  }

  /** 利用可能な対象を確認できるよう、日次案内項目一覧を一覧にする。 */
  public List<FeedBriefingItem> listBriefingItems(
      OffsetDateTime from, OffsetDateTime to, int maxItemsPerFeed) {
    return jdbcClient
        .sql(
            """
        SELECT id, title, url, description, content, published_at, feed_name
        FROM (
          SELECT i.id, i.title, i.url, i.description, i.content, i.published_at,
                 COALESCE(NULLIF(f.display_name, ''), NULLIF(f.title, ''), f.url) AS feed_name,
                 ROW_NUMBER() OVER (PARTITION BY i.feed_id ORDER BY i.published_at DESC, i.id DESC) AS row_num
          FROM feed_items i
          JOIN feeds f ON f.id = i.feed_id
          WHERE f.enabled = 1
            AND i.published_at IS NOT NULL
            AND i.published_at >= ?
            AND i.published_at <= ?
        )
        WHERE row_num <= ?
        ORDER BY published_at DESC, id DESC
        """)
        .params(from.toString(), to.toString(), maxItemsPerFeed)
        .query(this::mapFeedBriefingItem)
        .list();
  }

  /** 対象を特定できるよう、日次案内項目を探す。 */
  public FeedBriefingItem findBriefingItem(long itemId) {
    return jdbcClient
        .sql(
            """
        SELECT i.id, i.title, i.url, i.description, i.content, i.published_at,
               COALESCE(NULLIF(f.display_name, ''), NULLIF(f.title, ''), f.url) AS feed_name
        FROM feed_items i
        JOIN feeds f ON f.id = i.feed_id
        WHERE i.id = ?
        """)
        .param(itemId)
        .query(this::mapFeedBriefingItem)
        .single();
  }

  /** 指定された対象を削除する。 */
  public void delete(long id) {
    jdbcClient.sql("DELETE FROM feed_fetch_failures WHERE feed_id = ?").param(id).update();
    jdbcClient.sql("DELETE FROM feed_items WHERE feed_id = ?").param(id).update();
    jdbcClient.sql("DELETE FROM feeds WHERE id = ?").param(id).update();
  }

  /** フィードの購読と収集において、存在別URLに該当するか判定する。 */
  private boolean existsByUrl(String url) {
    Long count =
        jdbcClient
            .sql("SELECT COUNT(*) FROM feeds WHERE url = ?")
            .param(url)
            .query(Long.class)
            .single();
    return count != null && count > 0;
  }

  /** フィードの購読と収集で用いる対応表フィードを返す。 */
  private Feed mapFeed(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
    String lastFetchedAt = rs.getString("last_fetched_at");
    String rawUrl = rs.getString("url");
    String displayName = rs.getString("display_name");

    return new Feed(
        rs.getLong("id"),
        safeFeedUrl(rawUrl),
        rs.getString("title"),
        rs.getString("site_url"),
        rs.getString("description"),
        rawUrl.equals(displayName) ? safeFeedUrl(rawUrl) : displayName,
        rs.getInt("enabled") != 0,
        OffsetDateTime.parse(rs.getString("created_at")),
        OffsetDateTime.parse(rs.getString("updated_at")),
        lastFetchedAt == null ? null : OffsetDateTime.parse(lastFetchedAt));
  }

  /** フィードの購読と収集で用いる対応表フィード項目を返す。 */
  private FeedItem mapFeedItem(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
    String publishedAt = rs.getString("published_at");
    return new FeedItem(
        rs.getLong("id"),
        rs.getLong("feed_id"),
        rs.getString("title"),
        rs.getString("url"),
        rs.getString("description"),
        rs.getString("content"),
        publishedAt == null ? null : OffsetDateTime.parse(publishedAt),
        OffsetDateTime.parse(rs.getString("fetched_at")),
        OffsetDateTime.parse(rs.getString("created_at")),
        OffsetDateTime.parse(rs.getString("updated_at")));
  }

  /** フィードの購読と収集で用いる安全性フィードURLを返す。 */
  private String safeFeedUrl(String url) {
    try {
      FeedUrlSafety.parse(url);
      return url;
    } catch (IllegalArgumentException e) {
      // Do not re-expose credentials in legacy rows via CLI, API or update result names.
      return "[invalid feed URL]";
    }
  }

  /** フィードの購読と収集で用いる対応表フィード取得失敗情報を返す。 */
  private FeedFetchFailure mapFeedFetchFailure(java.sql.ResultSet rs, int rowNum)
      throws java.sql.SQLException {
    int httpStatus = rs.getInt("http_status");
    return new FeedFetchFailure(
        rs.getLong("id"),
        rs.getLong("feed_id"),
        OffsetDateTime.parse(rs.getString("failed_at")),
        rs.getString("error_message"),
        rs.wasNull() ? null : httpStatus);
  }

  /** フィードの購読と収集で用いる対応表フィード日次案内項目を返す。 */
  private FeedBriefingItem mapFeedBriefingItem(java.sql.ResultSet rs, int rowNum)
      throws java.sql.SQLException {
    return new FeedBriefingItem(
        rs.getLong("id"),
        rs.getString("title"),
        rs.getString("url"),
        rs.getString("description"),
        rs.getString("content"),
        OffsetDateTime.parse(rs.getString("published_at")),
        rs.getString("feed_name"));
  }

  /** フィードの購読と収集で用いる重複の排除キーを返す。 */
  private String dedupeKey(FetchedFeedItem item) {
    if (item.url() != null && !item.url().isBlank()) {
      return "url:" + item.url().trim();
    }
    if (item.title() != null && !item.title().isBlank() && item.publishedAt() != null) {
      return "title-published:" + item.title().trim() + "|" + item.publishedAt();
    }
    return null;
  }

  /** フィードの購読と収集に関する初期化データ形式を処理する。 */
  private void initializeSchema(DataSource dataSource) {
    try (var connection = dataSource.getConnection();
        var statement = connection.createStatement()) {
      statement.executeUpdate(
          """
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
      statement.executeUpdate(
          """
          CREATE TABLE IF NOT EXISTS feed_items (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            feed_id INTEGER NOT NULL,
            title TEXT NOT NULL,
            url TEXT,
            description TEXT,
            content TEXT,
            published_at TEXT,
            fetched_at TEXT NOT NULL,
            created_at TEXT NOT NULL,
            updated_at TEXT NOT NULL,
            dedupe_key TEXT NOT NULL,
            FOREIGN KEY(feed_id) REFERENCES feeds(id) ON DELETE CASCADE
          )
          """);
      addColumnIfMissing(connection, "feed_items", "description", "TEXT");
      addColumnIfMissing(connection, "feed_items", "content", "TEXT");
      statement.executeUpdate(
          """
          CREATE UNIQUE INDEX IF NOT EXISTS idx_feed_items_dedupe_key
          ON feed_items(dedupe_key)
          """);
      statement.executeUpdate(
          """
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

  /** 新たな対象を管理できるよう、列条件付き未指定を追加する。 */
  private void addColumnIfMissing(
      java.sql.Connection connection, String table, String column, String type)
      throws java.sql.SQLException {
    try (var resultSet = connection.getMetaData().getColumns(null, null, table, column)) {
      if (resultSet.next()) {
        return;
      }
    }
    try (var statement = connection.createStatement()) {
      statement.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
    }
  }
}
