package dev.mikoto2000.rei.memory.configuration;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;

class MemoryDataSourceIntegrationTest {

  @Test
  void dataSourceCreatesDatabaseFile() throws Exception {
    DataSource dataSource = new MemoryDataSourceConfiguration().memoryConsolidationDataSource();
    try (var connection = dataSource.getConnection()) {
      assertTrue(connection.isValid(1));
    }
    // global path を使う設計なので existence のみ確認
    assertTrue(Files.exists(dev.mikoto2000.rei.core.datasource.ReiPaths.memoryConsolidationDbPath()));
  }
}
