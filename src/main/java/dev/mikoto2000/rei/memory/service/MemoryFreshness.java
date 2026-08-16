package dev.mikoto2000.rei.memory.service;

import java.time.Duration;
import java.time.OffsetDateTime;

public record MemoryFreshness(
    Duration age,
    OffsetDateTime validUntil,
    FreshnessStatus status) {
}
