package dev.mikoto2000.rei.interest;

import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import javax.sql.DataSource;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class InterestUpdateService {

  private final JdbcClient jdbcClient;

  public InterestUpdateService(DataSource dataSource) {
    this.jdbcClient = JdbcClient.create(dataSource);
    initializeSchema(dataSource);
  }

  public boolean existsBySearchQuery(String searchQuery) {
    Integer count = jdbcClient.sql("SELECT COUNT(*) FROM interest_updates WHERE search_query = ?")
        .param(searchQuery)
        .query(Integer.class)
        .single();
    return count != null && count > 0;
  }

  public boolean existsByTopicWithinHours(String topic, int hours) {
    OffsetDateTime cutoff = utcCutoffHours(hours);
    Integer count = jdbcClient.sql("""
        SELECT COUNT(*) FROM interest_updates
        WHERE topic = ? AND created_at >= ?
        """)
        .params(topic, cutoff.toString())
        .query(Integer.class)
        .single();
    return count != null && count > 0;
  }

  public List<String> listRecentSearchQueries(int days) {
    OffsetDateTime cutoff = utcCutoffDays(days);
    return jdbcClient.sql("""
        SELECT DISTINCT search_query FROM interest_updates
        WHERE created_at >= ?
        ORDER BY created_at DESC
        """)
        .param(cutoff.toString())
        .query(String.class)
        .list();
  }

  public InterestUpdate saveWithCreatedAt(String topic, String reason, String searchQuery,
      String summary, List<String> sourceUrls, OffsetDateTime createdAt) {
    Long id = jdbcClient.sql("""
        INSERT INTO interest_updates (topic, reason, search_query, summary, source_urls, created_at)
        VALUES (?, ?, ?, ?, ?, ?)
        RETURNING id
        """)
        .params(topic, reason, searchQuery, summary, String.join("\n", sourceUrls), createdAt.toString())
        .query(Long.class)
        .single();
    return new InterestUpdate(id, topic, reason, searchQuery, summary, sourceUrls, createdAt);
  }

  public InterestUpdate save(String topic, String reason, String searchQuery, String summary, List<String> sourceUrls) {
    return saveWithCreatedAt(topic, reason, searchQuery, summary, sourceUrls, OffsetDateTime.now(ZoneOffset.UTC));
  }

  public List<InterestUpdate> listRecent(int recentHours) {
    OffsetDateTime cutoff = utcCutoffHours(recentHours);
    return jdbcClient.sql("""
        SELECT id, topic, reason, search_query, summary, source_urls, created_at
        FROM interest_updates
        WHERE created_at >= ?
        ORDER BY created_at DESC, id DESC
        """)
        .param(cutoff.toString())
        .query((rs, rowNum) -> new InterestUpdate(
            rs.getLong("id"),
            rs.getString("topic"),
            rs.getString("reason"),
            rs.getString("search_query"),
            rs.getString("summary"),
            splitUrls(rs.getString("source_urls")),
            OffsetDateTime.parse(rs.getString("created_at"))))
        .list();
  }

  private List<String> splitUrls(String sourceUrls) {
    if (sourceUrls == null || sourceUrls.isBlank()) {
      return List.of();
    }
    return List.of(sourceUrls.split("\\R"));
  }

  private OffsetDateTime utcCutoffHours(int hours) {
    return OffsetDateTime.now(ZoneOffset.UTC).minusHours(hours);
  }

  private OffsetDateTime utcCutoffDays(int days) {
    return OffsetDateTime.now(ZoneOffset.UTC).minusDays(days);
  }

  private void initializeSchema(DataSource dataSource) {
    try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
      statement.executeUpdate("""
          CREATE TABLE IF NOT EXISTS interest_updates (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            topic TEXT NOT NULL,
            reason TEXT NOT NULL,
            search_query TEXT NOT NULL UNIQUE,
            summary TEXT NOT NULL,
            source_urls TEXT NOT NULL,
            created_at TEXT NOT NULL
          )
          """);
    } catch (SQLException e) {
      throw new IllegalStateException("interest_updates テーブルの初期化に失敗しました", e);
    }
  }
}
