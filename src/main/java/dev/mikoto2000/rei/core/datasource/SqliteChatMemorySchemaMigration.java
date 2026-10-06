package dev.mikoto2000.rei.core.datasource;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Adds Spring AI 2.x message ordering to an existing SQLite chat-memory table. */
public final class SqliteChatMemorySchemaMigration {

  private SqliteChatMemorySchemaMigration() {
  }

  /** Must run before Spring AI's schema initializer creates the sequence-id index. */
  public static void migrate(DataSource dataSource) {
    var jdbc = new JdbcTemplate(dataSource);
    var transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    transaction.executeWithoutResult(status -> {
      var columns = jdbc.query("PRAGMA table_info(SPRING_AI_CHAT_MEMORY)", (row, index) -> row.getString("name"));
      if (columns.isEmpty()) {
        // Spring AI owns schema creation for new installations.
        return;
      }
      if (columns.stream().noneMatch("sequence_id"::equalsIgnoreCase)) {
        jdbc.execute("ALTER TABLE SPRING_AI_CHAT_MEMORY ADD COLUMN sequence_id INTEGER NOT NULL DEFAULT 0");
        // Keep every legacy row and its timestamp. rowid breaks timestamp ties in
        // insertion order, matching the old SQLite conversation/timestamp index.
        jdbc.update("""
            WITH ordered_messages AS (
              SELECT rowid AS message_rowid,
                     ROW_NUMBER() OVER (PARTITION BY conversation_id ORDER BY timestamp, rowid) - 1 AS sequence_id
              FROM SPRING_AI_CHAT_MEMORY
            )
            UPDATE SPRING_AI_CHAT_MEMORY
            SET sequence_id = (
              SELECT sequence_id FROM ordered_messages
              WHERE message_rowid = SPRING_AI_CHAT_MEMORY.rowid
            )
            """);
      }
      // Never renumber an already-migrated conversation: its sequence can differ
      // from timestamp order after Spring AI preserves message creation times.
      jdbc.execute("""
          CREATE INDEX IF NOT EXISTS SPRING_AI_CHAT_MEMORY_CONVERSATION_ID_SEQUENCE_ID_IDX
          ON SPRING_AI_CHAT_MEMORY(conversation_id, sequence_id)
          """);
    });
  }
}
