package dev.mikoto2000.rei.memory.service;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import dev.mikoto2000.rei.memory.configuration.MemoryProperties;

class MemoryServiceIntegrationTest {

  @Test
  void initializeSchemaCreatesRequiredTables() throws Exception {
    var tempDir = Files.createTempDirectory("rei-memory-it-");
    var ds = new DriverManagerDataSource("jdbc:sqlite:" + tempDir.resolve("memory-it.db"));
    var props = new MemoryProperties(true, 20, 80, 10, 3, 2000, 60,
        new MemoryProperties.ExpiryDefaults(30, 365));
    MemoryService service = new MemoryService(ds, props);

    JdbcClient jdbcClient = JdbcClient.create(ds);
    for (String table : java.util.List.of(
        "memories", "memory_tags", "memory_sources", "memory_relations", "memory_summaries", "memory_fts")) {
      Integer count = jdbcClient.sql("SELECT COUNT(*) FROM sqlite_master WHERE name = ?")
          .param(table)
          .query(Integer.class)
          .single();
      assertEquals(1, count, table + " should exist");
    }
    assertTrue(service.search("none", 10).isEmpty());
  }
}
