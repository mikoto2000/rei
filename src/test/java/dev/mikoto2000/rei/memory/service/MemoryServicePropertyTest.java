package dev.mikoto2000.rei.memory.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;


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

  // Feature: ai-memory-consolidation, Property 10: 論理削除の不変条件
  @Property(tries = 50)
  void deletedMemoryIsNotReturnedByListOrSearch(@ForAll @AlphaChars @NotBlank String token) {
    MemoryService service = newService("delete-" + System.nanoTime() + ".db");
    Memory saved = service.save(new Memory(null, token + " to-delete", MemoryType.KNOWLEDGE, MemoryScope.SHORT_TERM,
        MemoryStatus.CANDIDATE, 0.8d, null, null, null));
    service.updateStatus(saved.id(), MemoryStatus.DELETED);

    assertEquals(MemoryStatus.DELETED, service.findById(saved.id()).orElseThrow().status());
    assertTrue(service.listActive().stream().noneMatch(m -> m.id().equals(saved.id())));
    assertTrue(service.search(token, 10).stream().noneMatch(m -> m.id().equals(saved.id())));
  }

  // Feature: ai-memory-consolidation, Property 18: スコープ別デフォルト有効期限の設定
  @Property(tries = 50)
  void defaultExpiryDependsOnScope(@ForAll MemoryScope scope) {
    MemoryService service = newService("expiry-default-" + System.nanoTime() + ".db");
    OffsetDateTime before = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS);
    Memory saved = service.save(new Memory(null, "expiry-check", MemoryType.KNOWLEDGE, scope,
        MemoryStatus.CANDIDATE, 0.8d, null, null, null));
    OffsetDateTime expiresAt = saved.expiresAt();
    OffsetDateTime after = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS);

    if (scope == MemoryScope.SHORT_TERM) {
      assertTrue(expiresAt != null);
      assertTrue(!expiresAt.isBefore(before.plusDays(30).minusMinutes(1)));
      assertTrue(!expiresAt.isAfter(after.plusDays(30).plusMinutes(1)));
    } else if (scope == MemoryScope.LONG_TERM) {
      assertTrue(expiresAt != null);
      assertTrue(!expiresAt.isBefore(before.plusDays(365).minusMinutes(1)));
      assertTrue(!expiresAt.isAfter(after.plusDays(365).plusMinutes(1)));
    } else if (scope == MemoryScope.PERMANENT) {
      assertNull(expiresAt);
    }
  }

  // Feature: ai-memory-consolidation, Property 19: 有効期限切れ記憶の自動 deprecated 化
  @Property(tries = 20)
  void expiredMemoryBecomesDeprecated(@ForAll boolean ignored) {
    MemoryService service = newService("expiry-update-" + System.nanoTime() + ".db");
    Memory expired = service.save(new Memory(null, "expired", MemoryType.KNOWLEDGE, MemoryScope.SHORT_TERM,
        MemoryStatus.CANDIDATE, 0.8d, OffsetDateTime.now().minusDays(1), null, null));

    var active = service.listActiveWithExpiryCheck();
    assertTrue(active.stream().noneMatch(m -> m.id().equals(expired.id())));
    assertEquals(MemoryStatus.DEPRECATED, service.findById(expired.id()).orElseThrow().status());
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
