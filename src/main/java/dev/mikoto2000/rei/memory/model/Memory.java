package dev.mikoto2000.rei.memory.model;

import java.time.OffsetDateTime;

public record Memory(
    String id,
    String content,
    MemoryType type,
    MemoryScope scope,
    MemoryStatus status,
    double confidence,
    OffsetDateTime expiresAt,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    OffsetDateTime observedAt,
    OffsetDateTime validUntil) {

  public Memory(String id, String content, MemoryType type, MemoryScope scope, MemoryStatus status, double confidence,
      OffsetDateTime expiresAt, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    this(id, content, type, scope, status, confidence, expiresAt, createdAt, updatedAt, createdAt, expiresAt);
  }
}
