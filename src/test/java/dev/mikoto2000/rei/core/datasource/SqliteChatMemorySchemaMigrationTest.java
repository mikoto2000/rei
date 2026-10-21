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

/** SQLiteチャット記憶データ形式移行の結果と状態の契約を検証するテスト。 */
@Tag("integration")
class SqliteChatMemorySchemaMigrationTest {

  /** 検証に使う発生順序位置。 */
  private static final String SEQUENCE_INDEX =
      "SPRING_AI_CHAT_MEMORY_CONVERSATION_ID_SEQUENCE_ID_IDX";

  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path tempDir;

  /** 検証に使うデータ取得元。 */
  private SQLiteDataSource dataSource;

  /** 検証に使うデータベースへの接続。 */
  private JdbcTemplate jdbc;

  /** 各検証を独立した状態で始められるよう、依存先と検証データを準備する。 */
  @BeforeEach
  void setUp() {
    dataSource = new SQLiteDataSource();
    dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("memory.db"));
    jdbc = new JdbcTemplate(dataSource);
  }

  /** データ保存先の利用で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void leavesNewDatabaseForSpringAiToInitialize() {
    SqliteChatMemorySchemaMigration.migrate(dataSource);

    assertThat(jdbc.queryForList("PRAGMA table_info(SPRING_AI_CHAT_MEMORY)")).isEmpty();
    initializeSpringAiSchema();
    var repository = repository();
    repository.saveAll("new", List.of(new UserMessage("hello")));
    assertThat(repository.findByConversationId("new"))
        .extracting(Message::getText)
        .containsExactly("hello");
  }

  /** 全対象旧形式行一覧を保持することを確認する。 */
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
    var originalRows =
        jdbc.queryForList(
            "SELECT rowid, conversation_id, content, type, timestamp FROM SPRING_AI_CHAT_MEMORY ORDER BY rowid");

    SqliteChatMemorySchemaMigration.migrate(dataSource);
    initializeSpringAiSchema();

    assertThat(
            jdbc.queryForList(
                "SELECT rowid, conversation_id, content, type, timestamp FROM SPRING_AI_CHAT_MEMORY ORDER BY rowid"))
        .isEqualTo(originalRows);
    assertThat(contents("a"))
        .containsExactly("first", "same timestamp", "same timestamp", "legacy tool", "later");
    assertThat(contents("b")).containsExactly("other first", "other later");
    assertThat(
            jdbc.queryForList(
                "SELECT sequence_id FROM SPRING_AI_CHAT_MEMORY WHERE conversation_id = 'a' ORDER BY sequence_id",
                Long.class))
        .containsExactly(0L, 1L, 2L, 3L, 4L);
    assertThat(
            jdbc.queryForList(
                "SELECT sequence_id FROM SPRING_AI_CHAT_MEMORY WHERE conversation_id = 'b' ORDER BY sequence_id",
                Long.class))
        .containsExactly(0L, 1L);
    assertSequenceIndex();
  }

  /** データ保存先の利用で、指定した条件に対する結果と状態の変化を確認する。 */
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

    assertThat(repository.findByConversationId("a"))
        .extracting(Message::getText)
        .containsExactly("question", "answer", "follow-up");
    assertThat(repository.findByConversationId("b"))
        .extracting(Message::getText)
        .containsExactly("unrelated");
    assertThat(
            jdbc.queryForObject(
                "SELECT timestamp FROM SPRING_AI_CHAT_MEMORY WHERE content = 'question'",
                Long.class))
        .isEqualTo(1000L);
  }

  /** 繰り返された移行について、既存の発生順序を保持することを確認する。 */
  @Test
  void repeatedMigrationPreservesExistingSequenceEvenWhenItDiffersFromTimestampOrder() {
    createLegacySchema();
    insert("a", "older", "USER", 1000L);
    insert("a", "newer", "ASSISTANT", 2000L);
    SqliteChatMemorySchemaMigration.migrate(dataSource);
    var repository = repository();
    var messages = repository.findByConversationId("a");
    repository.saveAll("a", List.of(messages.get(1), messages.get(0)));
    var originalRows =
        jdbc.queryForList("SELECT rowid, * FROM SPRING_AI_CHAT_MEMORY ORDER BY rowid");

    SqliteChatMemorySchemaMigration.migrate(dataSource);
    SqliteChatMemorySchemaMigration.migrate(dataSource);

    assertThat(jdbc.queryForList("SELECT rowid, * FROM SPRING_AI_CHAT_MEMORY ORDER BY rowid"))
        .isEqualTo(originalRows);
    assertThat(repository.findByConversationId("a"))
        .extracting(Message::getText)
        .containsExactly("newer", "older");
    assertSequenceIndex();
  }

  /** 未指定位置を作成することを確認する。 */
  @Test
  void createsMissingIndexForExistingModernSchemaWithoutChangingRows() {
    initializeSpringAiSchema();
    repository().saveAll("modern", List.of(new UserMessage("existing")));
    jdbc.execute("DROP INDEX " + SEQUENCE_INDEX);
    var originalRows =
        jdbc.queryForList("SELECT rowid, * FROM SPRING_AI_CHAT_MEMORY ORDER BY rowid");

    SqliteChatMemorySchemaMigration.migrate(dataSource);

    assertThat(jdbc.queryForList("SELECT rowid, * FROM SPRING_AI_CHAT_MEMORY ORDER BY rowid"))
        .isEqualTo(originalRows);
    assertSequenceIndex();
  }

  /** データ保存先の利用で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void migratesEmptyLegacyTable() {
    createLegacySchema();

    SqliteChatMemorySchemaMigration.migrate(dataSource);
    initializeSpringAiSchema();
    repository().saveAll("new", List.of(new UserMessage("first")));

    assertThat(repository().findByConversationId("new"))
        .extracting(Message::getText)
        .containsExactly("first");
    assertSequenceIndex();
  }

  /** データ保存先の利用で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void rollsBackColumnAndBackfillIfIndexCreationFails() {
    createLegacySchema();
    insert("a", "preserved", "USER", 1000L);
    jdbc.execute("CREATE TABLE " + SEQUENCE_INDEX + " (value TEXT)");
    var originalRows =
        jdbc.queryForList("SELECT rowid, * FROM SPRING_AI_CHAT_MEMORY ORDER BY rowid");

    assertThatThrownBy(() -> SqliteChatMemorySchemaMigration.migrate(dataSource))
        .isInstanceOf(DataAccessException.class);

    assertThat(jdbc.queryForList("SELECT rowid, * FROM SPRING_AI_CHAT_MEMORY ORDER BY rowid"))
        .isEqualTo(originalRows);
    assertThat(jdbc.queryForList("PRAGMA table_info(SPRING_AI_CHAT_MEMORY)"))
        .extracting(row -> row.get("name"))
        .doesNotContain("sequence_id");
  }

  /** 指定された条件を再現するための検証対象や入力情報を作成する。 */
  private void createLegacySchema() {
    jdbc.execute(
        """
        CREATE TABLE SPRING_AI_CHAT_MEMORY (
          conversation_id TEXT NOT NULL,
          content TEXT NOT NULL,
          type TEXT NOT NULL,
          timestamp INTEGER NOT NULL,
          CHECK (type IN ('USER', 'ASSISTANT', 'SYSTEM', 'TOOL'))
        )
        """);
    jdbc.execute(
        """
        CREATE INDEX SPRING_AI_CHAT_MEMORY_CONVERSATION_ID_TIMESTAMP_IDX
        ON SPRING_AI_CHAT_MEMORY(conversation_id, timestamp)
        """);
  }

  /** {@code initializeSpringAiSchema} の条件を再現するため、検証用の操作や状態を扱う。 */
  private void initializeSpringAiSchema() {
    new ResourceDatabasePopulator(
            new ClassPathResource(
                "org/springframework/ai/chat/memory/repository/jdbc/schema-sqlite.sql"))
        .execute(dataSource);
  }

  /** {@code insert} の条件を再現するため、検証用の操作や状態を扱う。 */
  private void insert(String conversationId, String content, String type, long timestamp) {
    jdbc.update(
        "INSERT INTO SPRING_AI_CHAT_MEMORY (conversation_id, content, type, timestamp) VALUES (?, ?, ?, ?)",
        conversationId,
        content,
        type,
        timestamp);
  }

  /** {@code contents} の条件を再現するための補助情報を返す。 */
  private List<String> contents(String conversationId) {
    return jdbc.queryForList(
        "SELECT content FROM SPRING_AI_CHAT_MEMORY WHERE conversation_id = ? ORDER BY sequence_id",
        String.class,
        conversationId);
  }

  /** 検証で使用する保存と検索の窓口を、指定された条件に合わせて用意する。 */
  private JdbcChatMemoryRepository repository() {
    return JdbcChatMemoryRepository.builder().dataSource(dataSource).build();
  }

  /** 複数の検証で共通する期待結果を確認し、要件に合わない状態を検出する。 */
  private void assertSequenceIndex() {
    assertThat(jdbc.queryForList("PRAGMA index_info(" + SEQUENCE_INDEX + ")"))
        .extracting(row -> row.get("name"))
        .containsExactly("conversation_id", "sequence_id");
  }
}
