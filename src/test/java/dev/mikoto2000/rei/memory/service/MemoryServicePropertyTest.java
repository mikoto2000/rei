package dev.mikoto2000.rei.memory.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;


import dev.mikoto2000.rei.memory.configuration.MemoryProperties;
import dev.mikoto2000.rei.memory.model.Memory;
import dev.mikoto2000.rei.memory.model.MemoryScope;
import dev.mikoto2000.rei.memory.model.MemoryStatus;
import dev.mikoto2000.rei.memory.model.MemoryType;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.DoubleRange;
import net.jqwik.api.constraints.NotBlank;

class MemoryServicePropertyTest {

  // Feature: ai-memory-consolidation, Property 2: 承認記憶の保存ラウンドトリップ
  @Property(tries = 100)
  void saveFindRoundTrip(@ForAll @NotBlank String content, @ForAll MemoryType type,
      @ForAll MemoryScope scope, @ForAll @DoubleRange(min = 0.0, max = 1.0) double confidence) {
    MemoryService service = newService("roundtrip-" + System.nanoTime() + ".db");

    Memory saved = service.save(new Memory(null, content, type, scope, MemoryStatus.CANDIDATE, confidence, null, null, null));
    Memory found = service.findById(saved.id()).orElseThrow();

    assertEquals(content, found.content());
    assertEquals(type, found.type());
    assertEquals(scope, found.scope());
    assertEquals(confidence, found.confidence());
    assertEquals(MemoryStatus.ACTIVE, found.status());
  }

  // Feature: ai-memory-consolidation, Property 8: 検索結果の件数上限とフィルタリング
  @Property(tries = 50)
  void searchResultRespectsLimit(@ForAll @AlphaChars @NotBlank String token) {
    MemoryService service = newService("limit-" + System.nanoTime() + ".db");
    for (int i = 0; i < 15; i++) {
      service.save(new Memory(null, token + " value-" + i, MemoryType.KNOWLEDGE, MemoryScope.SHORT_TERM,
          MemoryStatus.CANDIDATE, 0.8d, null, null, null));
    }

    var results = service.search(token, 10);
    assertTrue(results.size() <= 10);
    assertTrue(results.stream().allMatch(m -> m.status() == MemoryStatus.ACTIVE));
  }

  private MemoryService newService(String dbName) {
    try {
      Path tempDir = Files.createTempDirectory("rei-memory-pbt-");
      var ds = new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:sqlite:" + tempDir.resolve(dbName));
      var props = new MemoryProperties(true, 20, 80, 10, 3, 2000, 60,
          new MemoryProperties.ExpiryDefaults(30, 365));
      return new MemoryService(ds, props);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
