package dev.mikoto2000.rei.memory.model;

import java.util.*;

public record MemoryCandidate(MemoryType type, MemoryScope scope, String content, String summary,
    double confidence, double importance, List<String> sourceTurnIds, List<String> tags) {
  public MemoryCandidate {
    if (type == null || !Set.of(MemoryType.FACT, MemoryType.PREFERENCE, MemoryType.DECISION,
        MemoryType.CONSTRAINT, MemoryType.PROJECT_STATE, MemoryType.PROCEDURE, MemoryType.LESSON,
        MemoryType.RELATION).contains(type)) throw new IllegalArgumentException("Invalid memory type");
    if (scope != MemoryScope.GLOBAL && scope != MemoryScope.PROJECT) throw new IllegalArgumentException("Invalid scope");
    if (content == null || content.isBlank() || content.length() > 4000 || summary == null
        || summary.isBlank() || summary.length() > 500) throw new IllegalArgumentException("Invalid memory text");
    if (!Double.isFinite(confidence) || confidence < 0 || confidence > 1
        || !Double.isFinite(importance) || importance < 0 || importance > 1) throw new IllegalArgumentException("Invalid score");
    sourceTurnIds = List.copyOf(sourceTurnIds);
    tags = List.copyOf(tags);
    if (sourceTurnIds.isEmpty() || sourceTurnIds.stream().anyMatch(String::isBlank)
        || tags.size() > 20 || tags.stream().anyMatch(t -> t.isBlank() || t.length() > 100))
      throw new IllegalArgumentException("Invalid sources or tags");
  }
}
