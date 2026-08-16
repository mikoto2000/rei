package dev.mikoto2000.rei.memory.service;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;

import org.springframework.stereotype.Service;

import dev.mikoto2000.rei.memory.model.Memory;

@Service
public class MemoryFreshnessService {
  private final Clock clock;

  public MemoryFreshnessService(Clock clock) {
    this.clock = clock;
  }

  public MemoryFreshness evaluate(Memory memory, Duration ttl) {
    OffsetDateTime now = OffsetDateTime.now(clock);
    OffsetDateTime observedAt = memory.observedAt() == null ? memory.createdAt() : memory.observedAt();
    Duration age = observedAt == null ? Duration.ZERO : Duration.between(observedAt, now);
    OffsetDateTime validUntil = memory.validUntil();
    if (validUntil != null) {
      return new MemoryFreshness(age, validUntil, validUntil.isBefore(now) ? FreshnessStatus.STALE : FreshnessStatus.FRESH);
    }
    if (ttl == null) {
      return new MemoryFreshness(age, null, FreshnessStatus.NO_EXPIRATION);
    }
    return new MemoryFreshness(age, observedAt.plus(ttl), age.compareTo(ttl) <= 0 ? FreshnessStatus.FRESH : FreshnessStatus.STALE);
  }
}
