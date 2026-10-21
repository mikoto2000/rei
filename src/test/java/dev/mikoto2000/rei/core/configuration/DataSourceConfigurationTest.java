package dev.mikoto2000.rei.core.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;

import dev.mikoto2000.rei.core.sqlitevec.SqliteVecDataSource;
import dev.mikoto2000.rei.core.sqlitevec.SqliteVecExtensionLoader;
import java.nio.file.Path;
import java.sql.Connection;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** データ取得元機能の構成の結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class DataSourceConfigurationTest {

  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path tempDir;

  /** データ取得元について、記憶データベースを利用することを確認する。 */
  @Test
  void dataSourceUsesMemoryDatabase() throws Exception {
    DataSourceConfiguration configuration = new DataSourceConfiguration();

    DataSource dataSource = configuration.dataSource(tempDir);

    try (Connection connection = dataSource.getConnection()) {
      assertEquals(
          "jdbc:sqlite:" + tempDir.resolve("memory.db"), connection.getMetaData().getURL());
    }
  }

  /** 機能の組み立てで、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void vectorStoreDataSourceUsesDedicatedDatabaseAndSqliteVecWrapper() throws Exception {
    DataSourceConfiguration configuration = new DataSourceConfiguration();
    SqliteVecExtensionLoader loader = mock(SqliteVecExtensionLoader.class);
    doNothing().when(loader).load(org.mockito.ArgumentMatchers.any(Connection.class));

    DataSource dataSource = configuration.vectorStoreDataSource(tempDir, loader);

    assertInstanceOf(SqliteVecDataSource.class, dataSource);
    try (Connection connection = dataSource.getConnection()) {
      assertEquals(
          "jdbc:sqlite:" + tempDir.resolve("vectorstore.db") + "?enable_load_extension=true",
          connection.getMetaData().getURL());
    }
  }
}
