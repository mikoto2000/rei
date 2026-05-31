package dev.mikoto2000.rei.memory.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;

import org.springframework.jdbc.datasource.DriverManagerDataSource;

import dev.mikoto2000.rei.memory.configuration.MemoryProperties;
import dev.mikoto2000.rei.memory.model.Memory;
import dev.mikoto2000.rei.memory.model.MemoryScope;
import dev.mikoto2000.rei.memory.model.MemoryStatus;
import dev.mikoto2000.rei.memory.model.MemoryType;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;

class MemoryExporterPropertyTest {

  // Feature: ai-memory-consolidation, Property 15: エクスポート件数の一貫性
  @Property(tries = 30)
  void exportCountMatchesActiveMemoryCount(@ForAll @IntRange(min = 0, max = 20) int count) throws Exception {
    var tempDir = Files.createTempDirectory("rei-export-pbt-");
    var ds = new DriverManagerDataSource("jdbc:sqlite:" + tempDir.resolve("memory-export-prop.db"));
    var props = new MemoryProperties(true, 20, 80, 10, 3, 2000, 60,
        new MemoryProperties.ExpiryDefaults(30, 365));
    MemoryService service = new MemoryService(ds, props);

    for (int i = 0; i < count; i++) {
      service.save(new Memory(null, "content-" + i, MemoryType.KNOWLEDGE, MemoryScope.SHORT_TERM,
          MemoryStatus.CANDIDATE, 0.8d, null, null, null));
    }

    MemoryExporter exporter = new MemoryExporter(service);
    MemoryExporter.ExportResult result = exporter.export(tempDir.resolve("out"));

    assertEquals(count, result.count());
  }
}
