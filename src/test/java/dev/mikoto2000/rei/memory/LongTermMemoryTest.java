package dev.mikoto2000.rei.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.memory.configuration.MemoryProperties;
import dev.mikoto2000.rei.memory.model.MemoryCandidate;
import dev.mikoto2000.rei.memory.model.MemoryScope;
import dev.mikoto2000.rei.memory.model.MemorySource;
import dev.mikoto2000.rei.memory.model.MemoryStatus;
import dev.mikoto2000.rei.memory.model.MemoryType;
import dev.mikoto2000.rei.memory.service.MemoryRepository;
import dev.mikoto2000.rei.memory.service.MemoryService;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@org.junit.jupiter.api.Tag("integration")
class LongTermMemoryTest {
  @TempDir Path dir;
  MemoryRepository repository;
  MemoryProperties props = new MemoryProperties(true, 20, 80, 10, 3, 2000, 60, null);

  @BeforeEach
  void setup() {
    var ds = new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("memory.db"));
    repository = new MemoryRepository(ds, new MemoryService(ds, props));
  }

  static MemoryCandidate candidate(String content, MemoryScope scope) {
    return new MemoryCandidate(
        MemoryType.DECISION, scope, content, content, .95, .9, List.of("turn1"), List.of("vision"));
  }

  @Test
  void validatesCandidate() {
    assertThrows(IllegalArgumentException.class, () -> candidate("", MemoryScope.PROJECT));
    assertThrows(IllegalArgumentException.class, () -> candidate("x", MemoryScope.SESSION));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new MemoryCandidate(
                MemoryType.FACT,
                MemoryScope.GLOBAL,
                "x",
                "x",
                Double.NaN,
                .9,
                List.of("t"),
                List.of()));
  }

  @Test
  void persistsScopedMemoryWithSourcesAndTags() {
    var memory =
        repository.insert(candidate("Vision first", MemoryScope.PROJECT), "project1", "session1");
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

  @Test
  void transactionRollsBackMemoryAndCheckpoint() {
    assertThrows(
        IllegalStateException.class,
        () ->
            repository.transaction(
                () -> {
                  repository.insert(candidate("rollback", MemoryScope.GLOBAL), null, "s");
                  throw new IllegalStateException("failure");
                }));
    assertTrue(repository.list("p", 100, 0).isEmpty());
    assertEquals(0, repository.lastProcessed("s"));
  }
}
