package dev.mikoto2000.rei.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.conversation.ConversationTurnStore;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.memory.configuration.MemoryProperties;
import dev.mikoto2000.rei.memory.model.LongTermMemory;
import dev.mikoto2000.rei.memory.model.Memory;
import dev.mikoto2000.rei.memory.model.MemoryCandidate;
import dev.mikoto2000.rei.memory.model.MemoryScope;
import dev.mikoto2000.rei.memory.model.MemoryStatus;
import dev.mikoto2000.rei.memory.model.MemoryType;
import dev.mikoto2000.rei.memory.service.MemoryAction;
import dev.mikoto2000.rei.memory.service.MemoryCandidateExtractor;
import dev.mikoto2000.rei.memory.service.MemoryRepository;
import dev.mikoto2000.rei.memory.service.MemoryResolution;
import dev.mikoto2000.rei.memory.service.MemoryResolutionModel;
import dev.mikoto2000.rei.memory.service.MemoryResolver;
import dev.mikoto2000.rei.memory.service.MemoryRetriever;
import dev.mikoto2000.rei.memory.service.MemoryService;
import dev.mikoto2000.rei.memory.service.SleepService;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 記憶統合の結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class MemoryConsolidationTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path dir;

  /** 検証に使う保存と検索の窓口。 */
  MemoryRepository repository;

  /** 検証に使うデータベース接続。 */
  DriverManagerDataSource ds;

  /** 検証に使うやり取り一覧。 */
  ConversationTurnStore turns = ConversationTurnStore.inMemory();

  /** 検証に使うprops。 */
  MemoryProperties props = new MemoryProperties(true, 20, 80, 10, 3, 2000, 60, null);

  /** 検証に使うモデル。 */
  MemoryResolutionModel model = mock(MemoryResolutionModel.class);

  /** 検証に使う対象の解決。 */
  MemoryResolver resolver;

  /** 各検証を独立した状態で始められるよう、依存先と検証データを準備する。 */
  @BeforeEach
  void setup() {
    ds = new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("memory.db"));
    repository = new MemoryRepository(ds, new MemoryService(ds, props));
    resolver = new MemoryResolver(model, props);
    var context = new AgentRunContext("turn1", "s", dir, "p");
    turns.start(context, "Vision first");
    turns.finish(context, ConversationTurnStore.Status.COMPLETED, "agreed");
  }

  /** {@code existing} の条件を再現するための補助情報を返す。 */
  LongTermMemory existing(String text) {
    return repository.insert(LongTermMemoryTest.candidate(text, MemoryScope.PROJECT), "p", "old");
  }

  /** 検証用に、長期記憶の活用の操作を実行する。 */
  SleepService.Report run(String text, MemoryAction action, List<String> targets) {
    when(model.resolve(any(), anyList())).thenReturn(new MemoryResolution(action, targets));
    return new SleepService(
            repository,
            turns,
            t -> List.of(LongTermMemoryTest.candidate(text, MemoryScope.PROJECT)),
            resolver,
            props)
        .sleep("s", "p", false);
  }

  /** 長期記憶の活用で、再送や重複を、受付と実行の規則に従って扱うことを確認する。 */
  @Test
  void duplicateDoesNotCreateRowAndAddsSource() {
    var old = existing("Vision first");
    run("Vision first", MemoryAction.NEW, List.of());
    verifyNoInteractions(model);
    assertEquals(1, repository.list("p", 10, 0).size());
    assertEquals(2, repository.find(old.id()).orElseThrow().sources().size());
  }

  /** 完全一致の重複の有無範囲外検索範囲引き続きについて、元のを利用することを確認する。 */
  @Test
  void exactDuplicateOutsideSearchWindowStillUsesOriginal() {
    var old = existing("Vision first");
    for (int i = 0; i < 60; i++) existing("Vision alternative " + i);
    run("Vision first", MemoryAction.NEW, List.of());
    assertEquals(61, repository.list("p", 100, 0).size());
    assertEquals(2, repository.find(old.id()).orElseThrow().sources().size());
    verifyNoInteractions(model);
  }

  /** 更新について、識別情報を保持することを確認する。 */
  @Test
  void updatePreservesIdentityAndOriginalSource() {
    var old = existing("Vision first");
    run("Vision first with fallback", MemoryAction.UPDATE, List.of(old.id()));
    var updated = repository.find(old.id()).orElseThrow();
    assertEquals("Vision first with fallback", updated.content());
    assertEquals(old.createdAt(), updated.createdAt());
    assertEquals(2, updated.sources().size());
    assertEquals(1, repository.search("fallback", "p", 10).size());
  }

  /** 元のを保持することを確認する。 */
  @Test
  void supersedeRetainsOriginalAndLinksNewDecision() {
    var old = existing("Vision disabled");
    run("Vision enabled", MemoryAction.SUPERSEDE, List.of(old.id()));
    var replaced = repository.find(old.id()).orElseThrow();
    assertEquals(MemoryStatus.SUPERSEDED, replaced.status());
    assertNotNull(replaced.supersededBy());
    assertNotNull(replaced.validUntil());
    assertEquals(1, repository.search("Vision", "p", 10).size());
  }

  /** 長期記憶の活用で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void mergeCombinesSourcesAndSupersedesBoth() {
    var a = existing("Vision Windows");
    var b = existing("Vision voice input");
    run("Vision Windows voice input", MemoryAction.MERGE, List.of(a.id(), b.id()));
    var active = repository.list("p", 10, 0);
    assertEquals(1, active.size());
    assertEquals(2, active.getFirst().sources().size());
    assertEquals(2, repository.relations(active.getFirst().id()).size());
  }

  /** 競合について、両方を保持することを確認する。 */
  @Test
  void conflictPreservesBothButDoesNotInjectUnresolvedClaims() {
    var old = existing("Vision disabled");
    var report = run("Vision enabled", MemoryAction.CONFLICT, List.of(old.id()));
    assertEquals(1, report.run().conflicts());
    assertEquals(MemoryStatus.ACTIVE, repository.find(old.id()).orElseThrow().status());
    assertEquals(2, repository.list("p", 10, 0).size());
    assertTrue(new MemoryRetriever(repository, props).retrieve("Vision", "p").memories().isEmpty());
  }

  /** 出力先所属対象範囲解決結果対象一覧を受け付けないことを確認する。 */
  @Test
  void thresholdIgnoresLowValueAndRejectsOutOfScopeResolutionTargets() {
    var low =
        new MemoryCandidate(
            MemoryType.FACT,
            MemoryScope.GLOBAL,
            "Vision",
            "Vision",
            .2,
            .9,
            List.of("turn1"),
            List.of());
    assertEquals(MemoryAction.IGNORE, resolver.resolve(low, List.of(), "p").action());
    var old = existing("Vision disabled");
    when(model.resolve(any(), anyList()))
        .thenReturn(new MemoryResolution(MemoryAction.UPDATE, List.of("foreign")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            resolver.resolve(
                LongTermMemoryTest.candidate("Vision enabled", MemoryScope.PROJECT),
                List.of(old),
                "p"));
  }

  /** 長期記憶の活用で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void rollbackIncludesMemoriesAndCheckpointWhenRunInsertFails() {
    JdbcClient.create(ds)
        .sql(
            "CREATE TRIGGER fail_run BEFORE INSERT ON sleep_runs BEGIN SELECT RAISE(ABORT,'injected'); END")
        .update();
    assertThrows(RuntimeException.class, () -> run("Vision new", MemoryAction.NEW, List.of()));
    assertTrue(repository.list("p", 10, 0).isEmpty());
    assertEquals(0, repository.lastProcessed("s"));
  }

  /** 状態の保存について、保存と検索の窓口再開の後も対象の情報が残ることを確認する。 */
  @Test
  void persistenceSurvivesRepositoryRestartAndKeepsLegacyData() {
    var old = existing("Vision persistent");
    var legacy = new MemoryService(ds, props);
    var legacyRow =
        legacy.save(
            new Memory(
                null,
                "legacy",
                MemoryType.KNOWLEDGE,
                MemoryScope.SHORT_TERM,
                MemoryStatus.ACTIVE,
                .9,
                null,
                null,
                null));
    var reopened = new MemoryRepository(ds, legacy);
    assertEquals(old, reopened.find(old.id()).orElseThrow());
    assertTrue(legacy.findById(legacyRow.id()).isPresent());
    assertEquals(1, reopened.list("p", 20, 0).size());
  }

  /** 長期記憶の活用で、競合する要求でも、実行順序と作業の分離を守ることを確認する。 */
  @Test
  void sameSessionConcurrentSleepIsRejected() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    MemoryCandidateExtractor extractor =
        t -> {
          entered.countDown();
          try {
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("timeout");
          } catch (InterruptedException e) {
            throw new CancellationException();
          }
          return List.of();
        };
    var service = new SleepService(repository, turns, extractor, resolver, props);
    try (var executor = Executors.newSingleThreadExecutor()) {
      var first = executor.submit(() -> service.sleep("s", "p", false));
      try {
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        assertThrows(IllegalStateException.class, () -> service.sleep("s", "p", false));
      } finally {
        release.countDown();
      }
      first.get(5, TimeUnit.SECONDS);
    }
    assertEquals(1, repository.history("p", 10).size());
  }
}
