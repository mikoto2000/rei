package dev.mikoto2000.rei.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verifyNoInteractions;

import dev.mikoto2000.rei.conversation.ConversationTurnStore;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.memory.configuration.MemoryProperties;
import dev.mikoto2000.rei.memory.model.MemoryScope;
import dev.mikoto2000.rei.memory.service.MemoryCandidateExtractor;
import dev.mikoto2000.rei.memory.service.MemoryRepository;
import dev.mikoto2000.rei.memory.service.MemoryResolutionModel;
import dev.mikoto2000.rei.memory.service.MemoryResolver;
import dev.mikoto2000.rei.memory.service.MemoryRetriever;
import dev.mikoto2000.rei.memory.service.MemoryService;
import dev.mikoto2000.rei.memory.service.SleepService;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 記憶境界条件の結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class MemoryBoundaryTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path dir;

  /** 評価値既定値を保持することを確認する。 */
  @Test
  void partiallySpecifiedSleepConfigurationRetainsScoreDefaults() {
    var binder =
        new org.springframework.boot.context.properties.bind.Binder(
            new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(
                Map.of("rei.memory.sleep.max-turns", "3")));
    var properties =
        binder
            .bind(
                "rei.memory",
                org.springframework.boot.context.properties.bind.Bindable.of(
                    MemoryProperties.class))
            .get();
    assertEquals(.70, properties.sleep().minConfidence());
    assertEquals(.50, properties.sleep().minImportance());
    assertEquals(3, properties.sleep().maxTurns());
  }

  /** 長期記憶の活用で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void disabledSleepDoesNotReadOrExtract() {
    var repository = mock(MemoryRepository.class);
    var turns = mock(ConversationTurnStore.class);
    var extractor = mock(MemoryCandidateExtractor.class);
    var resolver = mock(MemoryResolver.class);
    var properties = new MemoryProperties(false, 20, 80, 10, 3, 2000, 60, null);
    assertThrows(
        IllegalStateException.class,
        () ->
            new SleepService(repository, turns, extractor, resolver, properties)
                .sleep("s", "p", false));
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(repository, turns, extractor, resolver);
  }

  /** 長期記憶の活用の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void interruptDuringPersistenceRollsBackAndRestoresInterrupt() {
    var props = new MemoryProperties(true, 20, 80, 10, 3, 2000, 60, null);
    var ds = new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("m.db"));
    var repository = spy(new MemoryRepository(ds, new MemoryService(ds, props)));
    var turns = ConversationTurnStore.inMemory();
    var owner = new AgentRunContext("turn1", "s", dir, "p");
    turns.start(owner, "Vision");
    turns.finish(owner, ConversationTurnStore.Status.COMPLETED, "agreed");
    doAnswer(
            a -> {
              Object result = a.callRealMethod();
              Thread.currentThread().interrupt();
              return result;
            })
        .when(repository)
        .insert(any(), anyString(), anyString());
    var service =
        new SleepService(
            repository,
            turns,
            t -> List.of(LongTermMemoryTest.candidate("Vision", MemoryScope.PROJECT)),
            new MemoryResolver(mock(MemoryResolutionModel.class), props),
            props);
    try {
      assertThrows(
          java.util.concurrent.CancellationException.class, () -> service.sleep("s", "p", false));
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
    assertTrue(repository.list("p", 10, 0).isEmpty());
    assertEquals(0, repository.lastProcessed("s"));
  }

  /** 長期記憶の活用で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void batchLimitLeavesRemainderForNextManualSleep() {
    var props =
        new MemoryProperties(
            true,
            20,
            80,
            10,
            3,
            2000,
            60,
            null,
            null,
            new MemoryProperties.Sleep(.7, .5, 1, 12000, 120));
    var ds = new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("m.db"));
    var repository = new MemoryRepository(ds, new MemoryService(ds, props));
    var turns = ConversationTurnStore.inMemory();
    for (String id : List.of("one", "two")) {
      var context = new AgentRunContext(id, "s", dir, "p");
      turns.start(context, "request");
      turns.finish(context, ConversationTurnStore.Status.COMPLETED, "reply");
    }
    var service =
        new SleepService(
            repository,
            turns,
            t -> List.of(),
            new MemoryResolver(mock(MemoryResolutionModel.class), props),
            props);
    assertEquals(1, service.sleep("s", "p", false).run().processedTurns());
    assertEquals(1, service.unsleptTurns("s"));
    assertEquals(1, service.sleep("s", "p", false).run().processedTurns());
    assertEquals(0, service.unsleptTurns("s"));
  }

  /** 長期記憶の活用で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void supersededAndFutureDatedMemoriesAreNotRetrieved() {
    var props = new MemoryProperties(true, 20, 80, 10, 3, 2000, 60, null);
    var ds = new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("m.db"));
    var repository = new MemoryRepository(ds, new MemoryService(ds, props));
    var old =
        repository.insert(
            LongTermMemoryTest.candidate("Vision old", MemoryScope.GLOBAL), null, "s");
    var next =
        repository.insert(
            LongTermMemoryTest.candidate("Vision future", MemoryScope.GLOBAL), null, "s");
    repository.supersede(old.id(), next.id(), "SUPERSEDE");
    org.springframework.jdbc.core.simple.JdbcClient.create(ds)
        .sql("UPDATE memories SET valid_from='2999-01-01T00:00:00Z' WHERE id=?")
        .param(next.id())
        .update();
    assertTrue(new MemoryRetriever(repository, props).retrieve("Vision", "p").memories().isEmpty());
  }
}
