package dev.mikoto2000.rei.memory.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.mikoto2000.rei.memory.model.Memory;
import dev.mikoto2000.rei.memory.model.MemoryScope;
import dev.mikoto2000.rei.memory.model.MemoryStatus;
import dev.mikoto2000.rei.memory.model.MemoryType;

class MemoryConflictResolverTest {

  private final MemoryConflictResolver resolver = new MemoryConflictResolver();

  @Test
  void duplicateWhenHighlySimilar() {
    Memory candidate = new Memory("1", "alpha beta gamma", MemoryType.KNOWLEDGE, MemoryScope.SHORT_TERM, MemoryStatus.CANDIDATE,
        0.9d, null, null, null);
    Memory existing = new Memory("2", "alpha beta gamma", MemoryType.KNOWLEDGE, MemoryScope.SHORT_TERM, MemoryStatus.ACTIVE,
        0.9d, null, null, null);

    var result = resolver.check(candidate, List.of(existing));
    assertEquals(MemoryConflictResolver.ConflictType.DUPLICATE, result.type());
  }

  @Test
  void noneWhenDissimilar() {
    Memory candidate = new Memory("1", "java spring", MemoryType.KNOWLEDGE, MemoryScope.SHORT_TERM, MemoryStatus.CANDIDATE,
        0.9d, null, null, null);
    Memory existing = new Memory("2", "football soccer", MemoryType.KNOWLEDGE, MemoryScope.SHORT_TERM, MemoryStatus.ACTIVE,
        0.9d, null, null, null);

    var result = resolver.check(candidate, List.of(existing));
    assertEquals(MemoryConflictResolver.ConflictType.NONE, result.type());
  }
}
