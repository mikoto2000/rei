package dev.mikoto2000.rei.memory.configuration;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

class MemoryDataSourceConfigurationTest {

  @Test
  void memoryConsolidationDataSourceIsCreated() throws Exception {
    MemoryDataSourceConfiguration configuration = new MemoryDataSourceConfiguration();
    assertNotNull(configuration.memoryConsolidationDataSource());
  }
}
