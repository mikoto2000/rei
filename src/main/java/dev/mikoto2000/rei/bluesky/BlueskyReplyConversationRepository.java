package dev.mikoto2000.rei.bluesky;

import java.time.OffsetDateTime;
import java.util.List;

import javax.sql.DataSource;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import dev.mikoto2000.rei.conversation.ConversationLogStore;
import dev.mikoto2000.rei.llm.ConversationIds;

@Repository
public class BlueskyReplyConversationRepository {

  private final JdbcClient jdbcClient;
  private final ConversationLogStore conversationLogStore;

  public BlueskyReplyConversationRepository(DataSource dataSource) {
    this(dataSource, null);
  }

  @Autowired
  public BlueskyReplyConversationRepository(DataSource dataSource, ConversationLogStore conversationLogStore) {
    this.jdbcClient = JdbcClient.create(dataSource);
    this.conversationLogStore = conversationLogStore;
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

  public List<String> listHandles() {
    return jdbcClient.sql("""
        SELECT DISTINCT handle
        FROM bluesky_reply_conversations
        ORDER BY handle
        """)
        .query(String.class)
        .list();
  }

  private void append(String handle, String role, String content) {
    jdbcClient.sql("""
        INSERT INTO bluesky_reply_conversations(handle, role, content, created_at)
        VALUES (?, ?, ?, ?)
        """)
        .params(handle, role, content, OffsetDateTime.now().toString())
        .update();
    if (conversationLogStore != null) {
      conversationLogStore.append(ConversationIds.blueskyReply(handle), role, content);
    }
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
