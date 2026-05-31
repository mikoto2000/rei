package dev.mikoto2000.rei.bluesky;

import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;

import javax.sql.DataSource;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class BlueskyReplyStateRepository {

  private final JdbcClient jdbcClient;

  public BlueskyReplyStateRepository(DataSource dataSource) {
    this.jdbcClient = JdbcClient.create(dataSource);
    initializeSchema();
  }

  public Optional<UserState> findLastSeen(String handle) {
    return jdbcClient.sql("SELECT handle, last_seen_post_uri, last_seen_indexed_at, updated_at FROM bluesky_reply_user_state WHERE handle = ?")
        .param(handle)
        .query(this::mapUserState)
        .optional();
  }

  public void saveLastSeen(String handle, String postUri, OffsetDateTime indexedAt) {
    String now = OffsetDateTime.now().toString();
    int updated = jdbcClient.sql("UPDATE bluesky_reply_user_state SET last_seen_post_uri = ?, last_seen_indexed_at = ?, updated_at = ? WHERE handle = ?")
        .params(postUri, indexedAt == null ? null : indexedAt.toString(), now, handle)
        .update();
    if (updated == 0) {
      jdbcClient.sql("INSERT INTO bluesky_reply_user_state(handle, last_seen_post_uri, last_seen_indexed_at, updated_at) VALUES (?, ?, ?, ?)")
          .params(handle, postUri, indexedAt == null ? null : indexedAt.toString(), now)
          .update();
    }
  }

  public boolean isAlreadyReplied(String postUri) {
    Integer count = jdbcClient.sql("SELECT COUNT(*) FROM bluesky_replied_posts WHERE post_uri = ?")
        .param(postUri)
        .query(Integer.class)
        .single();
    return count != null && count > 0;
  }

  public void markReplied(String postUri, String handle, String repliedPostUri) {
    jdbcClient.sql("INSERT OR IGNORE INTO bluesky_replied_posts(post_uri, handle, replied_post_uri, replied_at) VALUES (?, ?, ?, ?)")
        .params(postUri, handle, repliedPostUri, OffsetDateTime.now().toString())
        .update();
  }

  public int countToday(String handle, LocalDate date) {
    Integer count = jdbcClient.sql("SELECT count FROM bluesky_reply_daily_count WHERE handle = ? AND date = ?")
        .params(handle, date.toString())
        .query(Integer.class)
        .optional()
        .orElse(0);
    return count == null ? 0 : count;
  }

  public void incrementToday(String handle, LocalDate date) {
    int updated = jdbcClient.sql("UPDATE bluesky_reply_daily_count SET count = count + 1 WHERE handle = ? AND date = ?")
        .params(handle, date.toString())
        .update();
    if (updated == 0) {
      jdbcClient.sql("INSERT INTO bluesky_reply_daily_count(handle, date, count) VALUES (?, ?, 1)")
          .params(handle, date.toString())
          .update();
    }
  }

  private void initializeSchema() {
    jdbcClient.sql("""
        CREATE TABLE IF NOT EXISTS bluesky_reply_user_state (
          handle TEXT PRIMARY KEY,
          last_seen_post_uri TEXT,
          last_seen_indexed_at TEXT,
          updated_at TEXT NOT NULL
        )
        """).update();
    jdbcClient.sql("""
        CREATE TABLE IF NOT EXISTS bluesky_replied_posts (
          post_uri TEXT PRIMARY KEY,
          handle TEXT NOT NULL,
          replied_post_uri TEXT,
          replied_at TEXT NOT NULL
        )
        """).update();
    jdbcClient.sql("""
        CREATE TABLE IF NOT EXISTS bluesky_reply_daily_count (
          handle TEXT NOT NULL,
          date TEXT NOT NULL,
          count INTEGER NOT NULL,
          PRIMARY KEY(handle, date)
        )
        """).update();
  }

  private UserState mapUserState(ResultSet rs, int rowNum) throws java.sql.SQLException {
    return new UserState(
        rs.getString("handle"),
        rs.getString("last_seen_post_uri"),
        fromString(rs.getString("last_seen_indexed_at")),
        fromString(rs.getString("updated_at")));
  }

  private OffsetDateTime fromString(String value) {
    return value == null ? null : OffsetDateTime.parse(value);
  }

  public record UserState(String handle, String lastSeenPostUri, OffsetDateTime lastSeenIndexedAt, OffsetDateTime updatedAt) {
  }
}
