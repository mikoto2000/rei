package dev.mikoto2000.rei.interest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ConversationHistoryServiceTest {

  @TempDir
  java.nio.file.Path tempDir;

  @Test
  void findRecentUserMessagesReturnsRecentUserMessagesOnly() throws Exception {
    DriverManagerDataSource dataSource = new DriverManagerDataSource("jdbc:sqlite:" + tempDir.resolve("memory.db"));
    initializeSchema(dataSource);
    long now = java.time.Instant.now().toEpochMilli();

    try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
      statement.executeUpdate(String.format("""
          INSERT INTO SPRING_AI_CHAT_MEMORY (conversation_id, content, type, timestamp)
          VALUES
          ('c1', 'vim の devcontainer を改善したい', 'USER', %d),
          ('c1', '了解しました', 'ASSISTANT', %d),
          ('c2', 'neovim plugin の最近動向が気になる', 'USER', %d)
          """, now - 2_000, now - 1_000, now));
    }

    ConversationHistoryService service = new ConversationHistoryService(dataSource);

    List<ConversationSnippet> snippets = service.findRecentUserMessages(30, 10);

    assertEquals(2, snippets.size());
    assertEquals("vim の devcontainer を改善したい", snippets.get(0).text());
    assertEquals("neovim plugin の最近動向が気になる", snippets.get(1).text());
    assertEquals("c1", snippets.get(0).conversationId());
  }

  private void initializeSchema(DriverManagerDataSource dataSource) throws Exception {
    try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
      statement.executeUpdate("""
          CREATE TABLE IF NOT EXISTS SPRING_AI_CHAT_MEMORY (
            conversation_id TEXT NOT NULL,
            content TEXT NOT NULL,
            type TEXT NOT NULL,
            timestamp INTEGER NOT NULL
          )
          """);
    }
  }
}
