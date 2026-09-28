package dev.mikoto2000.rei.memory.model;

import java.time.OffsetDateTime;
import java.util.List;

/** Extended view of the existing memories table; legacy rows remain available to legacy APIs. */
public record LongTermMemory(String id, MemoryScope scope, String projectId, MemoryType type,
    String content, String summary, double confidence, double importance, MemoryStatus status,
    OffsetDateTime createdAt, OffsetDateTime updatedAt, OffsetDateTime lastAccessedAt,
    OffsetDateTime validFrom, OffsetDateTime validUntil, String supersededBy,
    List<MemorySource> sources, List<String> tags) {
  public LongTermMemory { sources = List.copyOf(sources); tags = List.copyOf(tags); }
}
