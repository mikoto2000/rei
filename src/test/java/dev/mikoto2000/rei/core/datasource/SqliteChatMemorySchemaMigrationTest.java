package dev.mikoto2000.rei.core.datasource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.sqlite.SQLiteDataSource;

@Tag("integration")
class SqliteChatMemorySchemaMigrationTest {

  private static final String SEQUENCE_INDEX = "SPRING_AI_CHAT_MEMORY_CONVERSATION_ID_SEQUENCE_ID_IDX";

  @TempDir
  Path tempDir;

  private SQLiteDataSource dataSource;
  private JdbcTemplate jdbc;

  @BeforeEach
  void setUp() {
    dataSource = new SQLiteDataSource();
    dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("memory.db"));
    jdbc = new JdbcTemplate(dataSource);
  }

  @Test
  void leavesNewDatabaseForSpringAiToInitialize() {
    SqliteChatMemorySchemaMigration.migrate(dataSource);

    assertThat(jdbc.queryForList("PRAGMA table_info(SPRING_AI_CHAT_MEMORY)")).isEmpty();
    initializeSpringAiSchema();
    var repository = repository();
    repository.saveAll("new", List.of(new UserMessage("hello")));
    assertThat(repository.findByConversationId("new")).extracting(Message::getText).containsExactly("hello");
  }

  @Test
  void preservesAllLegacyRowsAndOrdersEachConversationByTimestampThenRowid() {
    createLegacySchema();
    insert("a", "later", "ASSISTANT", 3000L);
    insert("b", "other later", "ASSISTANT", 2000L);
    insert("a", "first", "USER", 1000L);
    insert("a", "same timestamp", "ASSISTANT", 1000L);
    insert("b", "other first", "USER", 1000L);
    insert("a", "same timestamp", "ASSISTANT", 1000L);
    insert("a", "legacy tool", "TOOL", 2000L);
    var originalRows = jdbc.queryForList(
        "SELECT rowid, conversation_id, content, type, timestamp FROM SPRING_AI_CHAT_MEMORY ORDER BY rowid");

    SqliteChatMemorySchemaMigration.migrate(dataSource);
    initializeSpringAiSchema();

    assertThat(jdbc.queryForList(
        "SELECT rowid, conversation_id, content, type, timestamp FROM SPRING_AI_CHAT_MEMORY ORDER BY rowid"))
        .isEqualTo(originalRows);
    assertThat(contents("a")).containsExactly("first", "same timestamp", "same timestamp", "legacy tool", "later");
    assertThat(contents("b")).containsExactly("other first", "other later");
    assertThat(jdbc.queryForList(
        "SELECT sequence_id FROM SPRING_AI_CHAT_MEMORY WHERE conversation_id = 'a' ORDER BY sequence_id", Long.class))
        .containsExactly(0L, 1L, 2L, 3L, 4L);
    assertThat(jdbc.queryForList(
        "SELECT sequence_id FROM SPRING_AI_CHAT_MEMORY WHERE conversation_id = 'b' ORDER BY sequence_id", Long.class))
        .containsExactly(0L, 1L);
    assertSequenceIndex();
  }

  @Test
  void migratedMessagesCanBeReadAndSavedWithSpringAiRepository() {
    createLegacySchema();
    insert("a", "answer", "ASSISTANT", 2000L);
    insert("a", "question", "USER", 1000L);
    insert("b", "unrelated", "USER", 1000L);
    SqliteChatMemorySchemaMigration.migrate(dataSource);
    var repository = repository();

    var messages = new ArrayList<>(repository.findByConversationId("a"));
    assertThat(messages).extracting(Message::getText).containsExactly("question", "answer");
    assertThat(messages.getFirst().getMetadata().get(JdbcChatMemoryRepository.CONVERSATION_TS))
        .isEqualTo(Instant.ofEpochMilli(1000L));
    messages.add(new UserMessage("follow-up"));
    repository.saveAll("a", messages);

    assertThat(repository.findByConversationId("a")).extracting(Message::getText)
        .containsExactly("question", "answer", "follow-up");
    assertThat(repository.findByConversationId("b")).extracting(Message::getText).containsExactly("unrelated");
    assertThat(jdbc.queryForObject(
        "SELECT timestamp FROM SPRING_AI_CHAT_MEMORY WHERE content = 'question'", Long.class)).isEqualTo(1000L);
  }

  @Test
  void repeatedMigrationPreservesExistingSequenceEvenWhenItDiffersFromTimestampOrder() {
    createLegacySchema();
    insert("a", "older", "USER", 1000L);
    insert("a", "newer", "ASSISTANT", 2000L);
    SqliteChatMemorySchemaMigration.migrate(dataSource);
    var repository = repository();
    var messages = repository.findByConversationId("a");
    repository.saveAll("a", List.of(messages.get(1), messages.get(0)));
    var originalRows = jdbc.queryForList("SELECT rowid, * FROM SPRING_AI_CHAT_MEMORY ORDER BY rowid");

    SqliteChatMemorySchemaMigration.migrate(dataSource);
    SqliteChatMemorySchemaMigration.migrate(dataSource);

    assertThat(jdbc.queryForList("SELECT rowid, * FROM SPRING_AI_CHAT_MEMORY ORDER BY rowid")).isEqualTo(originalRows);
    assertThat(repository.findByConversationId("a")).extracting(Message::getText).containsExactly("newer", "older");
    assertSequenceIndex();
  }

  @Test
  void createsMissingIndexForExistingModernSchemaWithoutChangingRows() {
    initializeSpringAiSchema();
    repository().saveAll("modern", List.of(new UserMessage("existing")));
    jdbc.execute("DROP INDEX " + SEQUENCE_INDEX);
    var originalRows = jdbc.queryForList("SELECT rowid, * FROM SPRING_AI_CHAT_MEMORY ORDER BY rowid");

    SqliteChatMemorySchemaMigration.migrate(dataSource);

    assertThat(jdbc.queryForList("SELECT rowid, * FROM SPRING_AI_CHAT_MEMORY ORDER BY rowid")).isEqualTo(originalRows);
    assertSequenceIndex();
  }

  @Test
  void migratesEmptyLegacyTable() {
    createLegacySchema();

    SqliteChatMemorySchemaMigration.migrate(dataSource);
    initializeSpringAiSchema();
    repository().saveAll("new", List.of(new UserMessage("first")));

    assertThat(repository().findByConversationId("new")).extracting(Message::getText).containsExactly("first");
    assertSequenceIndex();
  }

  @Test
  void rollsBackColumnAndBackfillIfIndexCreationFails() {
    createLegacySchema();
    insert("a", "preserved", "USER", 1000L);
    jdbc.execute("CREATE TABLE " + SEQUENCE_INDEX + " (value TEXT)");
    var originalRows = jdbc.queryForList("SELECT rowid, * FROM SPRING_AI_CHAT_MEMORY ORDER BY rowid");

    assertThatThrownBy(() -> SqliteChatMemorySchemaMigration.migrate(dataSource)).isInstanceOf(DataAccessException.class);

    assertThat(jdbc.queryForList("SELECT rowid, * FROM SPRING_AI_CHAT_MEMORY ORDER BY rowid")).isEqualTo(originalRows);
    assertThat(jdbc.queryForList("PRAGMA table_info(SPRING_AI_CHAT_MEMORY)"))
        .extracting(row -> row.get("name")).doesNotContain("sequence_id");
  }

  private void createLegacySchema() {
    jdbc.execute("""
        CREATE TABLE SPRING_AI_CHAT_MEMORY (
          conversation_id TEXT NOT NULL,
          content TEXT NOT NULL,
          type TEXT NOT NULL,
          timestamp INTEGER NOT NULL,
          CHECK (type IN ('USER', 'ASSISTANT', 'SYSTEM', 'TOOL'))
        )
        """);
    jdbc.execute("""
        CREATE INDEX SPRING_AI_CHAT_MEMORY_CONVERSATION_ID_TIMESTAMP_IDX
        ON SPRING_AI_CHAT_MEMORY(conversation_id, timestamp)
        """);
  }

  private void initializeSpringAiSchema() {
    new ResourceDatabasePopulator(new ClassPathResource(
        "org/springframework/ai/chat/memory/repository/jdbc/schema-sqlite.sql")).execute(dataSource);
  }

  private void insert(String conversationId, String content, String type, long timestamp) {
    jdbc.update("INSERT INTO SPRING_AI_CHAT_MEMORY (conversation_id, content, type, timestamp) VALUES (?, ?, ?, ?)",
        conversationId, content, type, timestamp);
  }

  private List<String> contents(String conversationId) {
    return jdbc.queryForList("SELECT content FROM SPRING_AI_CHAT_MEMORY WHERE conversation_id = ? ORDER BY sequence_id",
        String.class, conversationId);
  }

  private JdbcChatMemoryRepository repository() {
    return JdbcChatMemoryRepository.builder().dataSource(dataSource).build();
  }

  private void assertSequenceIndex() {
    assertThat(jdbc.queryForList("PRAGMA index_info(" + SEQUENCE_INDEX + ")"))
        .extracting(row -> row.get("name")).containsExactly("conversation_id", "sequence_id");
  }
}
