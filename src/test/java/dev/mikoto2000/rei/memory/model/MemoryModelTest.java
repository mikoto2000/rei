package dev.mikoto2000.rei.memory.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;

class MemoryModelTest {

  @Test
  void memoryTypeHasExpectedValues() {
    assertEquals(14, MemoryType.values().length);
    assertTrue(java.util.EnumSet.allOf(MemoryType.class).containsAll(java.util.Set.of(MemoryType.FACT,
        MemoryType.PREFERENCE,MemoryType.DECISION,MemoryType.CONSTRAINT,MemoryType.PROJECT_STATE,
        MemoryType.PROCEDURE,MemoryType.LESSON,MemoryType.RELATION)));
    assertTrue(java.util.EnumSet.allOf(MemoryType.class).contains(MemoryType.USER_PREFERENCE));
  }

  @Test
  void memoryScopeHasExpectedValues() {
    assertEquals(6, MemoryScope.values().length);
    assertTrue(java.util.EnumSet.allOf(MemoryScope.class).contains(MemoryScope.GLOBAL));
    assertTrue(java.util.EnumSet.allOf(MemoryScope.class).contains(MemoryScope.PERMANENT));
  }

  @Test
  void memoryStatusHasExpectedValues() {
    assertEquals(6, MemoryStatus.values().length);
    assertTrue(java.util.EnumSet.allOf(MemoryStatus.class).contains(MemoryStatus.SUPERSEDED));
    assertTrue(java.util.EnumSet.allOf(MemoryStatus.class).contains(MemoryStatus.ACTIVE));
  }

  @Test
  void memoryRecordFieldsAccessible() {
    OffsetDateTime now = OffsetDateTime.now();
    Memory memory = new Memory("id", "content", MemoryType.KNOWLEDGE, MemoryScope.SHORT_TERM, MemoryStatus.CANDIDATE, 0.7d,
        null, now, now);
    assertEquals("id", memory.id());
    assertEquals("content", memory.content());
    assertEquals(MemoryType.KNOWLEDGE, memory.type());
    assertEquals(MemoryScope.SHORT_TERM, memory.scope());
    assertEquals(MemoryStatus.CANDIDATE, memory.status());
  }
}
