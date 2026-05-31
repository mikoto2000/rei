package dev.mikoto2000.rei.bluesky;

import java.time.OffsetDateTime;
import java.util.List;

import javax.sql.DataSource;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class BlueskyReplyConversationRepository {

  private final JdbcClient jdbcClient;

  public BlueskyReplyConversationRepository(DataSource dataSource) {
    this.jdbcClient = JdbcClient.create(dataSource);
    initializeSchema();
  }

  public void appendUserMessage(String handle, String content) {
    append(handle, "user", content);
  }

  public void appendAssistantMessage(String handle, String content) {
    append(handle, "assistant", content);
  }

  public List<ConversationMessage> findRecent(String handle, int limit) {
    int safeLimit = Math.max(1, limit);
    return jdbcClient.sql("""
        SELECT role, content, created_at
        FROM bluesky_reply_conversations
        WHERE handle = ?
        ORDER BY id DESC
        LIMIT ?
        """)
        .params(handle, safeLimit)
        .query((rs, rowNum) -> new ConversationMessage(
            rs.getString("role"),
            rs.getString("content"),
            OffsetDateTime.parse(rs.getString("created_at"))))
        .list()
        .reversed();
  }

  private void append(String handle, String role, String content) {
    jdbcClient.sql("""
        INSERT INTO bluesky_reply_conversations(handle, role, content, created_at)
        VALUES (?, ?, ?, ?)
        """)
        .params(handle, role, content, OffsetDateTime.now().toString())
        .update();
  }

  private void initializeSchema() {
    jdbcClient.sql("""
        CREATE TABLE IF NOT EXISTS bluesky_reply_conversations (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          handle TEXT NOT NULL,
          role TEXT NOT NULL,
          content TEXT NOT NULL,
          created_at TEXT NOT NULL
        )
        """).update();
  }

  public record ConversationMessage(String role, String content, OffsetDateTime createdAt) {
  }
}
