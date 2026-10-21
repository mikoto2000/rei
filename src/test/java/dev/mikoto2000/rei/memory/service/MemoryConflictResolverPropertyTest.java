package dev.mikoto2000.rei.memory.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.mikoto2000.rei.memory.model.Memory;
import dev.mikoto2000.rei.memory.model.MemoryScope;
import dev.mikoto2000.rei.memory.model.MemoryStatus;
import dev.mikoto2000.rei.memory.model.MemoryType;
import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.DoubleRange;

/** 記憶競合対象の解決Propertyの結果と状態の契約を検証するテスト。 */
class MemoryConflictResolverPropertyTest {

  /** 検証に使う対象の解決。 */
  private final MemoryConflictResolver resolver = new MemoryConflictResolver();

  // Feature: ai-memory-consolidation, Property 11: 類似度スコアに基づく矛盾・重複判定
  /** 長期記憶の保存と検索で、再送や重複を、受付と実行の規則に従って扱うことを確認する。 */
  @Property(tries = 100)
  void identicalTextsAreDuplicate(@ForAll String text) {
    Memory c = m("1", text);
    Memory e = m("2", text);
    assertEquals(
        MemoryConflictResolver.ConflictType.DUPLICATE, resolver.check(c, List.of(e)).type());
  }

  // Feature: ai-memory-consolidation, Property 11: 類似度スコアに基づく矛盾・重複判定
  /** 長期記憶の保存と検索で、指定した条件に対する結果と状態の変化を確認する。 */
  @Property(tries = 100)
  void disjointTextsAreNone(@ForAll @DoubleRange(min = 0.0, max = 1.0) double n) {
    String left = "alpha beta " + n;
    String right = "omega delta";
    Memory c = m("1", left);
    Memory e = m("2", right);
    assertEquals(MemoryConflictResolver.ConflictType.NONE, resolver.check(c, List.of(e)).type());
  }

  /** {@code m} の条件を再現するための補助情報を返す。 */
  private Memory m(String id, String content) {
    return new Memory(
        id,
        content,
        MemoryType.KNOWLEDGE,
        MemoryScope.SHORT_TERM,
        MemoryStatus.CANDIDATE,
        0.8d,
        null,
        null,
        null);
  }
}
