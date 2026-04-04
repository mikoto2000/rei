package dev.mikoto2000.rei.core.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;

import java.nio.file.Path;
import java.sql.Connection;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.mikoto2000.rei.core.sqlitevec.SqliteVecDataSource;
import dev.mikoto2000.rei.core.sqlitevec.SqliteVecExtensionLoader;

class DataSourceConfigurationTest {

  @TempDir
  Path tempDir;

  @Test
  void dataSourceUsesMemoryDatabase() throws Exception {
    DataSourceConfiguration configuration = new DataSourceConfiguration();

    DataSource dataSource = configuration.dataSource(tempDir);

    try (Connection connection = dataSource.getConnection()) {
      assertEquals("jdbc:sqlite:" + tempDir.resolve(".rei").resolve("memory.db"), connection.getMetaData().getURL());
    }
  }

  @Test
  void vectorStoreDataSourceUsesDedicatedDatabaseAndSqliteVecWrapper() throws Exception {
    DataSourceConfiguration configuration = new DataSourceConfiguration();
    SqliteVecExtensionLoader loader = mock(SqliteVecExtensionLoader.class);
    doNothing().when(loader).load(org.mockito.ArgumentMatchers.any(Connection.class));

    DataSource dataSource = configuration.vectorStoreDataSource(tempDir, loader);

    assertInstanceOf(SqliteVecDataSource.class, dataSource);
    try (Connection connection = dataSource.getConnection()) {
      assertEquals("jdbc:sqlite:" + tempDir.resolve(".rei").resolve("vectorstore.db") + "?enable_load_extension=true",
          connection.getMetaData().getURL());
    }
  }
}
