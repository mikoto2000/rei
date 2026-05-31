package dev.mikoto2000.rei.memory.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import dev.mikoto2000.rei.memory.configuration.MemoryProperties;
import dev.mikoto2000.rei.memory.model.Memory;
import dev.mikoto2000.rei.memory.model.MemoryScope;
import dev.mikoto2000.rei.memory.model.MemoryStatus;
import dev.mikoto2000.rei.memory.model.MemoryType;

class MemoryExporterTest {

  @TempDir
  Path tempDir;

  @Test
  void exportCreatesMarkdownAndJsonlFiles() {
    MemoryService service = newService();
    service.save(new Memory(null, "content1", MemoryType.KNOWLEDGE, MemoryScope.SHORT_TERM, MemoryStatus.CANDIDATE, 0.8d,
        null, OffsetDateTime.now(ZoneOffset.UTC), OffsetDateTime.now(ZoneOffset.UTC)));

    MemoryExporter exporter = new MemoryExporter(service);
    Path outDir = tempDir.resolve("out");
    MemoryExporter.ExportResult result = exporter.export(outDir);

    assertEquals(1, result.count());
    assertTrue(Files.exists(result.latestMd()));
    assertTrue(Files.exists(result.datedMd()));
    assertTrue(Files.exists(result.datedJsonl()));
  }

  @Test
  void exportHandlesNoActiveMemory() {
    MemoryService service = newService();
    MemoryExporter exporter = new MemoryExporter(service);

    MemoryExporter.ExportResult result = exporter.export(tempDir.resolve("empty"));
    assertEquals(0, result.count());
  }

  private MemoryService newService() {
    var ds = new DriverManagerDataSource("jdbc:sqlite:" + tempDir.resolve("memory-exporter.db"));
    var props = new MemoryProperties(true, 20, 80, 10, 3, 2000, 60,
        new MemoryProperties.ExpiryDefaults(30, 365));
    return new MemoryService(ds, props);
  }
}
