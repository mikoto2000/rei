package dev.mikoto2000.rei.memory.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import dev.mikoto2000.rei.memory.model.Memory;
import dev.mikoto2000.rei.memory.model.MemoryScope;
import dev.mikoto2000.rei.memory.model.MemoryStatus;
import dev.mikoto2000.rei.memory.model.MemoryType;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.DoubleRange;

class MemoryConflictResolverPropertyTest {

  private final MemoryConflictResolver resolver = new MemoryConflictResolver();

  // Feature: ai-memory-consolidation, Property 11: 類似度スコアに基づく矛盾・重複判定
  @Property(tries = 100)
  void identicalTextsAreDuplicate(@ForAll String text) {
    Memory c = m("1", text);
    Memory e = m("2", text);
    assertEquals(MemoryConflictResolver.ConflictType.DUPLICATE, resolver.check(c, List.of(e)).type());
  }

  // Feature: ai-memory-consolidation, Property 11: 類似度スコアに基づく矛盾・重複判定
  @Property(tries = 100)
  void disjointTextsAreNone(@ForAll @DoubleRange(min = 0.0, max = 1.0) double n) {
    String left = "alpha beta " + n;
    String right = "omega delta";
    Memory c = m("1", left);
    Memory e = m("2", right);
    assertEquals(MemoryConflictResolver.ConflictType.NONE, resolver.check(c, List.of(e)).type());
  }

  private Memory m(String id, String content) {
    return new Memory(id, content, MemoryType.KNOWLEDGE, MemoryScope.SHORT_TERM, MemoryStatus.CANDIDATE, 0.8d, null, null,
        null);
  }
}
