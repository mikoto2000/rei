package dev.mikoto2000.rei.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.memory.configuration.MemoryProperties;
import dev.mikoto2000.rei.memory.model.MemoryCandidate;
import dev.mikoto2000.rei.memory.model.MemoryScope;
import dev.mikoto2000.rei.memory.model.MemorySource;
import dev.mikoto2000.rei.memory.model.MemoryStatus;
import dev.mikoto2000.rei.memory.model.MemoryType;
import dev.mikoto2000.rei.memory.service.MemoryRepository;
import dev.mikoto2000.rei.memory.service.MemoryService;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 長いTerm記憶の結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class LongTermMemoryTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path dir;

  /** 検証に使う保存と検索の窓口。 */
  MemoryRepository repository;

  /** 検証に使うprops。 */
  MemoryProperties props = new MemoryProperties(true, 20, 80, 10, 3, 2000, 60, null);

  /** 各検証を独立した状態で始められるよう、依存先と検証データを準備する。 */
  @BeforeEach
  void setup() {
    var ds = new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("memory.db"));
    repository = new MemoryRepository(ds, new MemoryService(ds, props));
  }

  /** {@code candidate} の条件を再現するための補助情報を返す。 */
  static MemoryCandidate candidate(String content, MemoryScope scope) {
    return new MemoryCandidate(
        MemoryType.DECISION, scope, content, content, .95, .9, List.of("turn1"), List.of("vision"));
  }

  /** 長期記憶の活用で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void validatesCandidate() {
    assertThrows(IllegalArgumentException.class, () -> candidate("", MemoryScope.PROJECT));
    assertThrows(IllegalArgumentException.class, () -> candidate("x", MemoryScope.SESSION));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new MemoryCandidate(
                MemoryType.FACT,
                MemoryScope.GLOBAL,
                "x",
                "x",
                Double.NaN,
                .9,
                List.of("t"),
                List.of()));
  }

  /** 長期記憶の活用で、保存した情報を、再起動や復元後の処理へ正しく引き継ぐことを確認する。 */
  @Test
  void persistsScopedMemoryWithSourcesAndTags() {
    var memory =
        repository.insert(candidate("Vision first", MemoryScope.PROJECT), "project1", "session1");
    var found = repository.find(memory.id()).orElseThrow();
    assertEquals("project1", found.projectId());
    assertEquals(List.of(new MemorySource("session1", "turn1")), found.sources());
    assertEquals(List.of("vision"), found.tags());
    assertEquals(1, repository.search("vision", "project1", 10).size());
    assertTrue(repository.search("vision", "project2", 10).isEmpty());
    repository.archive(memory.id());
    assertTrue(repository.search("vision", "project1", 10).isEmpty());
    assertEquals(MemoryStatus.ARCHIVED, repository.find(memory.id()).orElseThrow().status());
  }

  /** 長期記憶の活用で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void transactionRollsBackMemoryAndCheckpoint() {
    assertThrows(
        IllegalStateException.class,
        () ->
            repository.transaction(
                () -> {
                  repository.insert(candidate("rollback", MemoryScope.GLOBAL), null, "s");
                  throw new IllegalStateException("failure");
                }));
    assertTrue(repository.list("p", 100, 0).isEmpty());
    assertEquals(0, repository.lastProcessed("s"));
  }
}
