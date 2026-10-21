package dev.mikoto2000.rei.memory.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.mikoto2000.rei.memory.model.Memory;
import dev.mikoto2000.rei.memory.model.MemoryScope;
import dev.mikoto2000.rei.memory.model.MemoryStatus;
import dev.mikoto2000.rei.memory.model.MemoryType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** 記憶情報の鮮度サービスの結果と状態の契約を検証するテスト。 */
class MemoryFreshnessServiceTest {

  /** 日時や期限の条件を再現するために使う検証時刻。 */
  private final Clock clock = Clock.fixed(Instant.parse("2026-08-16T16:40:00Z"), ZoneOffset.UTC);

  /** 検証に使うサービス。 */
  private final MemoryFreshnessService service = new MemoryFreshnessService(clock);

  /** 長期記憶の保存と検索で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void ttlWithinRangeIsFresh() {
    Memory memory = memory(OffsetDateTime.parse("2026-08-16T16:39:40Z"), null);

    MemoryFreshness actual = service.evaluate(memory, Duration.ofSeconds(30));

    assertEquals(FreshnessStatus.FRESH, actual.status());
    assertEquals(Duration.ofSeconds(20), actual.age());
  }

  /** 長期記憶の保存と検索で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void ttlExceededIsStale() {
    Memory memory = memory(OffsetDateTime.parse("2026-08-16T16:39:00Z"), null);

    MemoryFreshness actual = service.evaluate(memory, Duration.ofSeconds(30));

    assertEquals(FreshnessStatus.STALE, actual.status());
    assertEquals(Duration.ofSeconds(60), actual.age());
  }

  /** 長期記憶の保存と検索で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void missingTtlAndValidUntilMeansNoExpiration() {
    Memory memory = memory(OffsetDateTime.parse("2026-08-16T16:00:00Z"), null);

    MemoryFreshness actual = service.evaluate(memory, null);

    assertEquals(FreshnessStatus.NO_EXPIRATION, actual.status());
  }

  /** 長期記憶の保存と検索で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void validUntilBeforeNowIsStale() {
    Memory memory =
        memory(
            OffsetDateTime.parse("2026-08-16T16:39:40Z"),
            OffsetDateTime.parse("2026-08-16T16:39:59Z"));

    MemoryFreshness actual = service.evaluate(memory, null);

    assertEquals(FreshnessStatus.STALE, actual.status());
  }

  /** {@code memory} の条件を再現するための補助情報を返す。 */
  private Memory memory(OffsetDateTime observedAt, OffsetDateTime validUntil) {
    return new Memory(
        "id",
        "content",
        MemoryType.KNOWLEDGE,
        MemoryScope.SHORT_TERM,
        MemoryStatus.ACTIVE,
        0.9d,
        null,
        observedAt,
        observedAt,
        observedAt,
        validUntil);
  }
}
