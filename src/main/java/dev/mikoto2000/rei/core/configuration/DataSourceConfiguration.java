package dev.mikoto2000.rei.core.configuration;

import dev.mikoto2000.rei.core.datasource.ReiPaths;
import dev.mikoto2000.rei.core.datasource.SqliteChatMemorySchemaMigration;
import dev.mikoto2000.rei.core.sqlitevec.SqliteVecDataSource;
import dev.mikoto2000.rei.core.sqlitevec.SqliteVecExtensionLoader;
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

  /** 機能の組み立てで用いるデータ取得元を返す。 */
  @Bean
  @Primary
  public DataSource dataSource() throws Exception {
    return sqliteDataSource(ReiPaths.memoryDbPath());
  }

  /** 機能の組み立てで用いるベクトル保存先データ取得元を返す。 */
  @Bean
  @Qualifier("vectorStoreDataSource")
  @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
      name = "rei.embedding.enabled",
      havingValue = "true",
      matchIfMissing = true)
  public DataSource vectorStoreDataSource(SqliteVecExtensionLoader sqliteVecExtensionLoader)
      throws Exception {
    return new SqliteVecDataSource(
        sqliteVecCapableDataSource(ReiPaths.vectorStoreDbPath()), sqliteVecExtensionLoader);
  }

  /** 機能の組み立てで用いるデータ取得元を返す。 */
  DataSource dataSource(Path workDirectory) throws Exception {
    return sqliteDataSource(ReiPaths.memoryDbPath(workDirectory));
  }

  /** 機能の組み立てで用いるベクトル保存先データ取得元を返す。 */
  DataSource vectorStoreDataSource(
      Path workDirectory, SqliteVecExtensionLoader sqliteVecExtensionLoader) throws Exception {
    return new SqliteVecDataSource(
        sqliteVecCapableDataSource(ReiPaths.vectorStoreDbPath(workDirectory)),
        sqliteVecExtensionLoader);
  }

  /** 機能の組み立てで用いるSQLiteデータ取得元を返す。 */
  private DataSource sqliteDataSource(Path dbPath) throws Exception {
    ReiPaths.ensureParentDirectoryExists(dbPath);

    SQLiteDataSource dataSource = new SQLiteDataSource();
    dataSource.setUrl("jdbc:sqlite:" + dbPath.toString());
    SqliteChatMemorySchemaMigration.migrate(dataSource);
    return dataSource;
  }

  /** 機能の組み立てで用いるSQLiteベクトルCapableデータ取得元を返す。 */
  private DataSource sqliteVecCapableDataSource(Path dbPath) throws Exception {
    ReiPaths.ensureParentDirectoryExists(dbPath);

    SQLiteDataSource dataSource = new SQLiteDataSource();
    dataSource.setUrl("jdbc:sqlite:" + dbPath.toString() + "?enable_load_extension=true");
    return dataSource;
  }
}
