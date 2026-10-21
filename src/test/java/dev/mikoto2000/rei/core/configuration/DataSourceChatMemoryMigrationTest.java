package dev.mikoto2000.rei.core.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.sqlite.SQLiteDataSource;

/** データ取得元チャット記憶移行の結果と状態の契約を検証するテスト。 */
@Tag("integration")
class DataSourceChatMemoryMigrationTest {

  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path tempDir;

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
            new ClassPathResource(
                "org/springframework/ai/chat/memory/repository/jdbc/schema-sqlite.sql"))
        .execute(dataSource);
    var repository = JdbcChatMemoryRepository.builder().dataSource(dataSource).build();

    assertThat(repository.findByConversationId("existing"))
        .extracting(Message::getText)
        .containsExactly("saved conversation");
  }
}
