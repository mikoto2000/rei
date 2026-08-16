package dev.mikoto2000.rei.memory.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import dev.mikoto2000.rei.memory.model.Memory;
import dev.mikoto2000.rei.memory.model.MemoryScope;
import dev.mikoto2000.rei.memory.model.MemoryStatus;
import dev.mikoto2000.rei.memory.model.MemoryType;

class MemoryFreshnessServiceTest {

  private final Clock clock = Clock.fixed(Instant.parse("2026-08-16T16:40:00Z"), ZoneOffset.UTC);
  private final MemoryFreshnessService service = new MemoryFreshnessService(clock);

  @Test
  void ttlWithinRangeIsFresh() {
    Memory memory = memory(OffsetDateTime.parse("2026-08-16T16:39:40Z"), null);

    MemoryFreshness actual = service.evaluate(memory, Duration.ofSeconds(30));

    assertEquals(FreshnessStatus.FRESH, actual.status());
    assertEquals(Duration.ofSeconds(20), actual.age());
  }

  @Test
  void ttlExceededIsStale() {
    Memory memory = memory(OffsetDateTime.parse("2026-08-16T16:39:00Z"), null);

    MemoryFreshness actual = service.evaluate(memory, Duration.ofSeconds(30));

    assertEquals(FreshnessStatus.STALE, actual.status());
    assertEquals(Duration.ofSeconds(60), actual.age());
  }

  @Test
  void missingTtlAndValidUntilMeansNoExpiration() {
    Memory memory = memory(OffsetDateTime.parse("2026-08-16T16:00:00Z"), null);

    MemoryFreshness actual = service.evaluate(memory, null);

    assertEquals(FreshnessStatus.NO_EXPIRATION, actual.status());
  }

  @Test
  void validUntilBeforeNowIsStale() {
    Memory memory = memory(
        OffsetDateTime.parse("2026-08-16T16:39:40Z"),
        OffsetDateTime.parse("2026-08-16T16:39:59Z"));

    MemoryFreshness actual = service.evaluate(memory, null);

    assertEquals(FreshnessStatus.STALE, actual.status());
  }

  private Memory memory(OffsetDateTime observedAt, OffsetDateTime validUntil) {
    return new Memory("id", "content", MemoryType.KNOWLEDGE, MemoryScope.SHORT_TERM, MemoryStatus.ACTIVE,
        0.9d, null, observedAt, observedAt, observedAt, validUntil);
  }
}
