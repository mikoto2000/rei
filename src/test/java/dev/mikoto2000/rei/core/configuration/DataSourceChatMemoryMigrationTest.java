package dev.mikoto2000.rei.core.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mikoto2000.rei.chatmemory.JdbcConversationMemoryStore;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.Message;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.env.MockEnvironment;
import org.sqlite.SQLiteDataSource;

/** データ取得元チャット記憶移行の結果と状態の契約を検証するテスト。 */
@Tag("integration")
class DataSourceChatMemoryMigrationTest {

  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path tempDir;

  @Test
  void applicationMemoryKeepsJdbcPersistenceAndTheFormerDefaultWindow() {
    var source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + tempDir.resolve("window.db"));
    var configuration = new DataSourceConfiguration();
    var store =
        configuration.conversationMemoryStore(
            source,
            new JdbcTemplate(source),
            new MockEnvironment()
                .withProperty("spring.ai.chat.memory.repository.jdbc.initialize-schema", "always"));
    var memory = configuration.chatMemory(store);
    for (int i = 0; i < 21; i++)
      memory.add("a", new org.springframework.ai.chat.messages.UserMessage("turn " + i));
    assertThat(memory.get("a")).hasSize(20);
    assertThat(configuration.chatMemory(store).get("a"))
        .extracting(Message::getText)
        .containsExactlyElementsOf(
            java.util.stream.IntStream.range(1, 21).mapToObj(i -> "turn " + i).toList());
  }

  /** 機能の組み立てで、入力や保存形式を契約に従って解釈し、情報を受け渡すことを確認する。 */
  @Test
  void migratesLegacyMemoryBeforeSpringAiSchemaInitialization() throws Exception {
    var legacyDataSource = new SQLiteDataSource();
    legacyDataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("memory.db"));
    var jdbc = new JdbcTemplate(legacyDataSource);
    jdbc.execute(
        """
        CREATE TABLE SPRING_AI_CHAT_MEMORY (
          conversation_id TEXT NOT NULL, content TEXT NOT NULL, type TEXT NOT NULL, timestamp INTEGER NOT NULL)
        """);
    jdbc.update(
        "INSERT INTO SPRING_AI_CHAT_MEMORY VALUES ('existing', 'saved conversation', 'USER', 1000)");

    var dataSource = new DataSourceConfiguration().dataSource(tempDir);
    new ResourceDatabasePopulator(
            new ClassPathResource("dev/mikoto2000/rei/conversation/schema-sqlite.sql"))
        .execute(dataSource);
    var repository = new JdbcConversationMemoryStore(dataSource);

    assertThat(repository.findByConversationId("existing"))
        .extracting(Message::getText)
        .containsExactly("saved conversation");
  }

  @Test
  void initializesWithExistingSchemaPropertyAndKeepsSavedMessages() {
    var source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + tempDir.resolve("configured.db"));
    var environment =
        new MockEnvironment()
            .withProperty("spring.ai.chat.memory.repository.jdbc.initialize-schema", "always")
            .withProperty(
                "spring.ai.chat.memory.repository.jdbc.schema",
                "classpath:org/springframework/ai/chat/memory/repository/jdbc/schema-@@platform@@.sql");
    var configuration = new DataSourceConfiguration();
    var store =
        configuration.conversationMemoryStore(source, new JdbcTemplate(source), environment);
    store.saveAll(
        "existing",
        java.util.List.of(new org.springframework.ai.chat.messages.UserMessage("saved")));

    var reopened =
        configuration.conversationMemoryStore(source, new JdbcTemplate(source), environment);
    assertThat(reopened.findByConversationId("existing"))
        .extracting(Message::getText)
        .containsExactly("saved");
  }

  @Test
  void defaultAndNeverModesDoNotInitializeSqliteSchema() {
    for (String mode : java.util.List.of("embedded", "never")) {
      var source = new SQLiteDataSource();
      source.setUrl("jdbc:sqlite:" + tempDir.resolve(mode + ".db"));
      new DataSourceConfiguration()
          .conversationMemoryStore(
              source,
              new JdbcTemplate(source),
              new MockEnvironment()
                  .withProperty("spring.ai.chat.memory.repository.jdbc.initialize-schema", mode));
      assertThat(
              new JdbcTemplate(source)
                  .queryForObject(
                      "SELECT COUNT(*) FROM sqlite_master WHERE name = 'SPRING_AI_CHAT_MEMORY'",
                      Integer.class))
          .isZero();
    }
  }

  @Test
  void defaultAndExplicitContinueOnErrorRetainExistingInitializationBehavior() throws Exception {
    for (String setting : java.util.List.of("default", "true", "false")) {
      var source = new SQLiteDataSource();
      source.setUrl("jdbc:sqlite:" + tempDir.resolve(setting + ".db"));
      var jdbc = new JdbcTemplate(source);
      jdbc.execute("CREATE TABLE existing (id INTEGER)");
      Path schema = tempDir.resolve(setting + ".sql");
      Files.writeString(
          schema, "CREATE TABLE existing (id INTEGER); CREATE TABLE after_error (id INTEGER);");
      var environment =
          new MockEnvironment()
              .withProperty("spring.ai.chat.memory.repository.jdbc.initialize-schema", "always")
              .withProperty(
                  "spring.ai.chat.memory.repository.jdbc.schema", schema.toUri().toString());
      if (!setting.equals("default"))
        environment.withProperty(
            "spring.ai.chat.memory.repository.jdbc.continue-on-error", setting);
      if (setting.equals("false")) {
        assertThatThrownBy(() ->
                new DataSourceConfiguration()
                    .conversationMemoryStore(source, new JdbcTemplate(source), environment))
            .isInstanceOf(
                org.springframework.jdbc.datasource.init.ScriptStatementFailedException.class);
      } else {
        new DataSourceConfiguration()
            .conversationMemoryStore(source, new JdbcTemplate(source), environment);
        assertThat(
                jdbc.queryForObject(
                    "SELECT COUNT(*) FROM sqlite_master WHERE name = 'after_error'", Integer.class))
            .isEqualTo(1);
      }
    }
  }

  @Test
  void existingPlatformOverrideSelectsTheConfiguredSchemaResource() throws Exception {
    var source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + tempDir.resolve("platform.db"));
    Path schema = tempDir.resolve("schema-compatible.sql");
    Files.writeString(schema, "CREATE TABLE selected_platform (id INTEGER);");
    var environment =
        new MockEnvironment()
            .withProperty("spring.ai.chat.memory.repository.jdbc.initialize-schema", "always")
            .withProperty("spring.ai.chat.memory.repository.jdbc.platform", "compatible")
            .withProperty(
                "spring.ai.chat.memory.repository.jdbc.schema",
                tempDir.resolve("schema-@@platform@@.sql").toUri().toString());
    new DataSourceConfiguration()
        .conversationMemoryStore(source, new JdbcTemplate(source), environment);
    assertThat(
            new JdbcTemplate(source)
                .queryForObject(
                    "SELECT COUNT(*) FROM sqlite_master WHERE name = 'selected_platform'",
                    Integer.class))
        .isEqualTo(1);
  }
}
