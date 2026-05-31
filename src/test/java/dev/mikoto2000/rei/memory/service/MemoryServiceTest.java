package dev.mikoto2000.rei.memory.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import dev.mikoto2000.rei.memory.configuration.MemoryProperties;
import dev.mikoto2000.rei.memory.model.Memory;
import dev.mikoto2000.rei.memory.model.MemoryScope;
import dev.mikoto2000.rei.memory.model.MemoryStatus;
import dev.mikoto2000.rei.memory.model.MemoryType;

class MemoryServiceTest {

  @TempDir
  Path tempDir;

  private MemoryService service;

  @BeforeEach
  void setUp() {
    var ds = new DriverManagerDataSource("jdbc:sqlite:" + tempDir.resolve("memory-test.db"));
    var props = new MemoryProperties(true, 20, 80, 10, 3, 2000, 60,
        new MemoryProperties.ExpiryDefaults(30, 365));
    service = new MemoryService(ds, props);
  }

  @Test
  void saveAndFindByIdRoundTrip() {
    Memory saved = service.save(new Memory(null, "hello memory", MemoryType.KNOWLEDGE, MemoryScope.SHORT_TERM,
        MemoryStatus.CANDIDATE, 0.9d, null, null, null));

    assertNotNull(saved.id());
    var found = service.findById(saved.id()).orElseThrow();
    assertEquals("hello memory", found.content());
    assertEquals(MemoryStatus.ACTIVE, found.status());
  }

  @Test
  void searchReturnsActiveOnly() {
    Memory saved = service.save(new Memory(null, "alpha beta gamma", MemoryType.KNOWLEDGE, MemoryScope.SHORT_TERM,
        MemoryStatus.CANDIDATE, 0.9d, null, null, null));
    service.updateStatus(saved.id(), MemoryStatus.DELETED);

    List<Memory> results = service.search("alpha", 10);
    assertTrue(results.isEmpty());
  }

  @Test
  void expiryCheckDeprecatesExpiredMemory() {
    Memory saved = service.save(new Memory(null, "will expire", MemoryType.KNOWLEDGE, MemoryScope.SHORT_TERM,
        MemoryStatus.CANDIDATE, 0.9d, OffsetDateTime.now(ZoneOffset.UTC).minusDays(1), null, null));

    var active = service.listActiveWithExpiryCheck();
    assertTrue(active.isEmpty());
    assertEquals(MemoryStatus.DEPRECATED, service.findById(saved.id()).orElseThrow().status());
  }

  @Test
  void tagsSourcesRelationsAndSummaryCanBeStored() {
    Memory saved = service.save(new Memory(null, "with meta", MemoryType.KNOWLEDGE, MemoryScope.LONG_TERM,
        MemoryStatus.CANDIDATE, 0.8d, null, null, null));

    service.saveTags(saved.id(), List.of("java", "memory"));
    service.saveSource(saved.id(), "chat");
    service.saveRelation(saved.id(), saved.id(), "related");
    service.saveSummary(saved.id(), "summary text");

    assertEquals(List.of("java", "memory"), service.findTags(saved.id()));
    assertEquals(List.of("chat"), service.findSources(saved.id()));
    assertEquals(1, service.relationCount());
    assertEquals("summary text", service.findSummary(saved.id()).orElseThrow());
  }

  @Test
  void updateStatusReturnsFalseWhenIdMissing() {
    assertFalse(service.updateStatus("missing", MemoryStatus.DELETED));
  }
}
