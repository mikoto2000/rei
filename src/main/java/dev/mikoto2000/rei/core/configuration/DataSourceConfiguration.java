package dev.mikoto2000.rei.core.configuration;

import dev.mikoto2000.rei.core.datasource.ReiPaths;
import dev.mikoto2000.rei.core.datasource.SqliteChatMemorySchemaMigration;
import dev.mikoto2000.rei.core.sqlitevec.SqliteVecDataSource;
import dev.mikoto2000.rei.core.sqlitevec.SqliteVecExtensionLoader;
import java.io.IOException;
import java.nio.file.Path;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.sqlite.SQLiteDataSource;

/** データ取得元の構成要素を接続し、設定に従って利用できるようにする。 */
@Configuration
public class DataSourceConfiguration {

  /** Preserves the former JDBC-backed auto-configuration and its default 20-message window. */
  @Bean
  @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(
      dev.mikoto2000.rei.chatmemory.ConversationMemory.class)
  public dev.mikoto2000.rei.chatmemory.ConversationMemory chatMemory(
      dev.mikoto2000.rei.chatmemory.JdbcConversationMemoryStore store) {
    return dev.mikoto2000.rei.chatmemory.WindowedConversationMemory.withStore(store, 20);
  }

  /** 機能の組み立てで用いるデータ取得元を返す。 */
  @Bean
  @Primary
  public DataSource dataSource() throws IOException {
    return sqliteDataSource(ReiPaths.memoryDbPath());
  }

  /**
   * Retains initialization of the legacy SQLite memory table without deprecated auto-configuration.
   */
  @Bean
  public dev.mikoto2000.rei.chatmemory.JdbcConversationMemoryStore conversationMemoryStore(
      DataSource dataSource,
      org.springframework.jdbc.core.JdbcTemplate jdbcTemplate,
      org.springframework.core.env.Environment environment) {
    var properties = new MemorySchemaProperties();
    org.springframework.boot.context.properties.bind.Binder.get(environment)
        .bind(
            "spring.ai.chat.memory.repository.jdbc",
            org.springframework.boot.context.properties.bind.Bindable.ofInstance(properties));
    new org.springframework.boot.jdbc.init.PropertiesBasedDataSourceScriptDatabaseInitializer<>(
            dataSource, properties)
        .initializeDatabase();
    return dev.mikoto2000.rei.chatmemory.JdbcConversationMemoryStore.fromJdbcTemplate(jdbcTemplate);
  }

  /** Keeps the same property defaults and Boot initializer used by the former JDBC starter. */
  private static final class MemorySchemaProperties
      extends org.springframework.boot.jdbc.init.DatabaseInitializationProperties {
    @Override
    public String getDefaultSchemaLocation() {
      return "classpath:org/springframework/ai/chat/memory/repository/jdbc/schema-@@platform@@.sql";
    }
  }

  /** 機能の組み立てで用いるベクトル保存先データ取得元を返す。 */
  @Bean
  @Qualifier("vectorStoreDataSource")
  @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
      name = "rei.embedding.enabled",
      havingValue = "true",
      matchIfMissing = true)
  public DataSource vectorStoreDataSource(SqliteVecExtensionLoader sqliteVecExtensionLoader)
      throws IOException {
    return new SqliteVecDataSource(
        sqliteVecCapableDataSource(ReiPaths.vectorStoreDbPath()), sqliteVecExtensionLoader);
  }

  /** 機能の組み立てで用いるデータ取得元を返す。 */
  DataSource dataSource(Path workDirectory) throws IOException {
    return sqliteDataSource(ReiPaths.memoryDbPath(workDirectory));
  }

  /** 機能の組み立てで用いるベクトル保存先データ取得元を返す。 */
  DataSource vectorStoreDataSource(
      Path workDirectory, SqliteVecExtensionLoader sqliteVecExtensionLoader) throws IOException {
    return new SqliteVecDataSource(
        sqliteVecCapableDataSource(ReiPaths.vectorStoreDbPath(workDirectory)),
        sqliteVecExtensionLoader);
  }

  /** 機能の組み立てで用いるSQLiteデータ取得元を返す。 */
  private DataSource sqliteDataSource(Path dbPath) throws IOException {
    ReiPaths.ensureParentDirectoryExists(dbPath);

    SQLiteDataSource dataSource = new SQLiteDataSource();
    dataSource.setUrl("jdbc:sqlite:" + dbPath.toString());
    SqliteChatMemorySchemaMigration.migrate(dataSource);
    return dataSource;
  }

  /** 機能の組み立てで用いるSQLiteベクトルCapableデータ取得元を返す。 */
  private DataSource sqliteVecCapableDataSource(Path dbPath) throws IOException {
    ReiPaths.ensureParentDirectoryExists(dbPath);

    SQLiteDataSource dataSource = new SQLiteDataSource();
    dataSource.setUrl("jdbc:sqlite:" + dbPath.toString() + "?enable_load_extension=true");
    return dataSource;
  }
}
