package dev.mikoto2000.rei.memory.service;

import dev.mikoto2000.rei.memory.model.Memory;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Service;

/** 記憶情報の鮮度のユースケースを実現する。 */
@Service
public class MemoryFreshnessService {
  /** 期限や記録日時の判定に使う時刻の取得元。 */
  private final Clock clock;

  /** 指定された値や利用先を使って、MemoryFreshnessServiceを初期化する。 */
  public MemoryFreshnessService(Clock clock) {
    this.clock = clock;
  }

  /** 対象を判断基準に照らして評価する。 */
  public MemoryFreshness evaluate(Memory memory, Duration ttl) {
    OffsetDateTime now = OffsetDateTime.now(clock);
    OffsetDateTime observedAt =
        memory.observedAt() == null ? memory.createdAt() : memory.observedAt();
    Duration age = observedAt == null ? Duration.ZERO : Duration.between(observedAt, now);
    OffsetDateTime validUntil = memory.validUntil();
    if (validUntil != null) {
      return new MemoryFreshness(
          age,
          validUntil,
          validUntil.isBefore(now) ? FreshnessStatus.STALE : FreshnessStatus.FRESH);
    }
    if (ttl == null) {
      return new MemoryFreshness(age, null, FreshnessStatus.NO_EXPIRATION);
    }
    return new MemoryFreshness(
        age,
        observedAt.plus(ttl),
        age.compareTo(ttl) <= 0 ? FreshnessStatus.FRESH : FreshnessStatus.STALE);
  }
}
