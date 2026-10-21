package dev.mikoto2000.rei.memory.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.mikoto2000.rei.memory.model.Memory;
import dev.mikoto2000.rei.memory.model.MemoryScope;
import dev.mikoto2000.rei.memory.model.MemoryStatus;
import dev.mikoto2000.rei.memory.model.MemoryType;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 記憶競合対象の解決の結果と状態の契約を検証するテスト。 */
class MemoryConflictResolverTest {

  /** 検証に使う対象の解決。 */
  private final MemoryConflictResolver resolver = new MemoryConflictResolver();

  /** 長期記憶の保存と検索で、再送や重複を、受付と実行の規則に従って扱うことを確認する。 */
  @Test
  void duplicateWhenHighlySimilar() {
    Memory candidate =
        new Memory(
            "1",
            "alpha beta gamma",
            MemoryType.KNOWLEDGE,
            MemoryScope.SHORT_TERM,
            MemoryStatus.CANDIDATE,
            0.9d,
            null,
            null,
            null);
    Memory existing =
        new Memory(
            "2",
            "alpha beta gamma",
            MemoryType.KNOWLEDGE,
            MemoryScope.SHORT_TERM,
            MemoryStatus.ACTIVE,
            0.9d,
            null,
            null,
            null);

    var result = resolver.check(candidate, List.of(existing));
    assertEquals(MemoryConflictResolver.ConflictType.DUPLICATE, result.type());
  }

  /** 長期記憶の保存と検索で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void noneWhenDissimilar() {
    Memory candidate =
        new Memory(
            "1",
            "java spring",
            MemoryType.KNOWLEDGE,
            MemoryScope.SHORT_TERM,
            MemoryStatus.CANDIDATE,
            0.9d,
            null,
            null,
            null);
    Memory existing =
        new Memory(
            "2",
            "football soccer",
            MemoryType.KNOWLEDGE,
            MemoryScope.SHORT_TERM,
            MemoryStatus.ACTIVE,
            0.9d,
            null,
            null,
            null);

    var result = resolver.check(candidate, List.of(existing));
    assertEquals(MemoryConflictResolver.ConflictType.NONE, result.type());
  }
}
