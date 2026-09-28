package dev.mikoto2000.rei.memory;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import dev.mikoto2000.rei.memory.model.*;
import dev.mikoto2000.rei.memory.service.*;
import dev.mikoto2000.rei.memory.configuration.MemoryProperties;

class LongTermMemoryTest {
  @TempDir Path dir;
  MemoryRepository repository;
  MemoryProperties props = new MemoryProperties(true,20,80,10,3,2000,60,null);
  @BeforeEach void setup() {
    var ds = new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("memory.db"));
    repository = new MemoryRepository(ds, new MemoryService(ds, props));
  }
  static MemoryCandidate candidate(String content, MemoryScope scope) {
    return new MemoryCandidate(MemoryType.DECISION, scope, content, content, .95, .9,
        List.of("turn1"), List.of("vision"));
  }
  @Test void validatesCandidate() {
    assertThrows(IllegalArgumentException.class, () -> candidate("", MemoryScope.PROJECT));
    assertThrows(IllegalArgumentException.class, () -> candidate("x", MemoryScope.SESSION));
    assertThrows(IllegalArgumentException.class, () -> new MemoryCandidate(MemoryType.FACT, MemoryScope.GLOBAL,
        "x","x",Double.NaN,.9,List.of("t"),List.of()));
  }
  @Test void persistsScopedMemoryWithSourcesAndTags() {
    var memory = repository.insert(candidate("Vision first", MemoryScope.PROJECT), "project1", "session1");
    var found = repository.find(memory.id()).orElseThrow();
    assertEquals("project1", found.projectId());
    assertEquals(List.of(new MemorySource("session1", "turn1")), found.sources());
    assertEquals(List.of("vision"), found.tags());
    assertEquals(1, repository.search("vision", "project1", 10).size());
    assertTrue(repository.search("vision", "project2", 10).isEmpty());
    repository.archive(memory.id());
    assertTrue(repository.search("vision", "project1", 10).isEmpty());
    assertEquals(MemoryStatus.ARCHIVED, repository.find(memory.id()).orElseThrow().status());
  }
  @Test void transactionRollsBackMemoryAndCheckpoint() {
    assertThrows(IllegalStateException.class, () -> repository.transaction(() -> {
      repository.insert(candidate("rollback", MemoryScope.GLOBAL), null, "s");
      throw new IllegalStateException("failure");
    }));
    assertTrue(repository.list("p", 100, 0).isEmpty());
    assertEquals(0, repository.lastProcessed("s"));
  }
}
