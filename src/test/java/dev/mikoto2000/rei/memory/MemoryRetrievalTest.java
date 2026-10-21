package dev.mikoto2000.rei.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.core.contextbudget.TokenEstimator;
import dev.mikoto2000.rei.memory.configuration.MemoryProperties;
import dev.mikoto2000.rei.memory.model.MemoryCandidate;
import dev.mikoto2000.rei.memory.model.MemoryScope;
import dev.mikoto2000.rei.memory.model.MemoryType;
import dev.mikoto2000.rei.memory.service.MemoryRepository;
import dev.mikoto2000.rei.memory.service.MemoryRetriever;
import dev.mikoto2000.rei.memory.service.MemoryService;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 記憶関連情報の取得の結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class MemoryRetrievalTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path dir;

  /** 検証に使う保存と検索の窓口。 */
  MemoryRepository repository;

  /** 検証に使うこの機能の動作設定。 */
  MemoryProperties properties = new MemoryProperties(true, 20, 80, 10, 3, 2000, 60, null);

  /** 各検証を独立した状態で始められるよう、依存先と検証データを準備する。 */
  @BeforeEach
  void setup() {
    var ds = new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("m.db"));
    repository = new MemoryRepository(ds, new MemoryService(ds, properties));
  }

  /** 長期記憶の活用で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void selectsGlobalAndCurrentActiveOnlyWithinBudget() {
    var global =
        repository.insert(
            LongTermMemoryTest.candidate("Vision global", MemoryScope.GLOBAL), null, "s");
    var project =
        repository.insert(
            LongTermMemoryTest.candidate("Vision project", MemoryScope.PROJECT), "p", "s");
    repository.insert(
        LongTermMemoryTest.candidate("Vision private", MemoryScope.PROJECT), "other", "s");
    var archived =
        repository.insert(
            LongTermMemoryTest.candidate("Vision archived", MemoryScope.GLOBAL), null, "s");
    repository.archive(archived.id());
    var result = new MemoryRetriever(repository, properties).retrieve("Vision", "p");
    assertEquals(2, result.memories().size());
    assertEquals(project.id(), result.memories().getFirst().id());
    assertTrue(result.context().contains(global.content()));
    assertFalse(result.context().contains("private"));
    assertTrue(TokenEstimator.conservative().text(result.context()) + 8 <= 1500);
    assertNotNull(repository.find(global.id()).orElseThrow().lastAccessedAt());
  }

  /** 長期記憶の活用で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void respectsCountAndTinyTokenBudget() {
    for (int i = 0; i < 8; i++)
      repository.insert(LongTermMemoryTest.candidate("Vision " + i, MemoryScope.GLOBAL), null, "s");
    assertEquals(
        5, new MemoryRetriever(repository, properties).retrieve("Vision", "p").memories().size());
    var tiny =
        new MemoryProperties(
            true, 20, 80, 10, 3, 2000, 60, null, new MemoryProperties.Retrieval(1, 10), null);
    assertTrue(new MemoryRetriever(repository, tiny).retrieve("Vision", "p").memories().isEmpty());
    var single =
        new MemoryProperties(
            true, 20, 80, 10, 3, 2000, 60, null, new MemoryProperties.Retrieval(1, 1500), null);
    assertEquals(
        1, new MemoryRetriever(repository, single).retrieve("Vision", "p").memories().size());
  }

  /** 長期記憶の活用で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void disabledNeverQueriesRepository() {
    var disabled = new MemoryProperties(false, 20, 80, 10, 3, 2000, 60, null);
    assertEquals("", new MemoryRetriever(null, disabled).retrieve("Vision", "p").context());
  }

  /** 長期記憶の活用で、検索条件に応じて、必要な候補と根拠を選ぶことを確認する。 */
  @Test
  void japaneseSummaryAndTagsAreSearchableAndPunctuationIsSafe() {
    repository.insert(
        new MemoryCandidate(
            MemoryType.FACT,
            MemoryScope.GLOBAL,
            "安定した事実",
            "日本語の要約",
            .9,
            .9,
            java.util.List.of("t"),
            java.util.List.of("音声入力")),
        null,
        "s");
    assertEquals(1, repository.search("音声入力", "p", 10).size());
    assertEquals(1, repository.search("音声入力について覚えていることは？", "p", 10).size());
    assertEquals(1, repository.search("日本語", "p", 10).size());
    assertEquals(1, repository.search("要約", "p", 10).size());
    assertTrue(repository.search("\" OR * - ()", "p", 10).isEmpty());
  }
}
