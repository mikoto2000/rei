package dev.mikoto2000.rei.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.conversation.ConversationTurnStore;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.memory.configuration.MemoryProperties;
import dev.mikoto2000.rei.memory.model.MemoryScope;
import dev.mikoto2000.rei.memory.service.MemoryAction;
import dev.mikoto2000.rei.memory.service.MemoryCandidateExtractor;
import dev.mikoto2000.rei.memory.service.MemoryRepository;
import dev.mikoto2000.rei.memory.service.MemoryResolution;
import dev.mikoto2000.rei.memory.service.MemoryResolutionModel;
import dev.mikoto2000.rei.memory.service.MemoryResolver;
import dev.mikoto2000.rei.memory.service.MemoryService;
import dev.mikoto2000.rei.memory.service.SleepService;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 待機サービスの結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class SleepServiceTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path dir;

  /** 検証に使う保存と検索の窓口。 */
  MemoryRepository repository;

  /** 検証に使うやり取り一覧。 */
  ConversationTurnStore turns = ConversationTurnStore.inMemory();

  /** 検証に使う情報の抽出。 */
  MemoryCandidateExtractor extractor = mock(MemoryCandidateExtractor.class);

  /** 検証に使うモデル。 */
  MemoryResolutionModel model = mock(MemoryResolutionModel.class);

  /** 検証に使うprops。 */
  MemoryProperties props = new MemoryProperties(true, 20, 80, 10, 3, 2000, 60, null);

  /** 検証に使うサービス。 */
  SleepService service;

  /** 各検証を独立した状態で始められるよう、依存先と検証データを準備する。 */
  @BeforeEach
  void setup() {
    var ds = new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("memory.db"));
    repository = new MemoryRepository(ds, new MemoryService(ds, props));
    service =
        new SleepService(repository, turns, extractor, new MemoryResolver(model, props), props);
    when(extractor.extract(anyList()))
        .thenReturn(List.of(LongTermMemoryTest.candidate("Vision first", MemoryScope.PROJECT)));
    when(model.resolve(any(), anyList()))
        .thenReturn(new MemoryResolution(MemoryAction.NEW, List.of()));
  }

  /** {@code turn} の条件を再現するため、検証用の操作や状態を扱う。 */
  void turn(String id, ConversationTurnStore.Status status) {
    var context = new AgentRunContext(id, "s", dir, "p");
    turns.start(context, "Use Vision first");
    if (status != ConversationTurnStore.Status.RUNNING) turns.finish(context, status, "agreed");
  }

  /** 長期記憶の活用で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void previewDoesNotWriteAndSleepIsIncremental() {
    turn("turn1", ConversationTurnStore.Status.COMPLETED);
    var original = turns.read("s");
    assertEquals(1, service.sleep("s", "p", true).plans().size());
    assertEquals(0, repository.lastProcessed("s"));
    assertTrue(repository.history("p", 10).isEmpty());
    assertTrue(repository.list("p", 10, 0).isEmpty());
    service.sleep("s", "p", false);
    assertEquals(1, repository.lastProcessed("s"));
    assertEquals(1, repository.list("p", 10, 0).size());
    service.sleep("s", "p", false);
    verify(extractor, times(2)).extract(anyList());
    assertEquals(original, turns.read("s"));
    turn("turn2", ConversationTurnStore.Status.COMPLETED);
    when(extractor.extract(anyList()))
        .thenAnswer(
            a -> {
              List<ConversationTurnStore.Turn> input = a.getArgument(0);
              assertEquals(
                  List.of("turn2"), input.stream().map(ConversationTurnStore.Turn::runId).toList());
              return List.of();
            });
    service.sleep("s", "p", false);
    assertEquals(2, repository.lastProcessed("s"));
  }

  /** 長期記憶の活用で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void runningTurnBlocksWatermark() {
    turn("turn1", ConversationTurnStore.Status.RUNNING);
    turn("turn2", ConversationTurnStore.Status.COMPLETED);
    service.sleep("s", "p", false);
    verifyNoInteractions(extractor);
    assertEquals(0, repository.lastProcessed("s"));
  }

  /** 長期記憶の活用で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void invalidSourceRollsBackAndRecordsFailure() {
    turn("other", ConversationTurnStore.Status.COMPLETED);
    assertThrows(IllegalArgumentException.class, () -> service.sleep("s", "p", false));
    assertEquals(0, repository.lastProcessed("s"));
    assertTrue(repository.list("p", 10, 0).isEmpty());
    assertEquals("FAILED", repository.history("p", 10).getFirst().status());
  }

  /** 長期記憶の活用の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void cancelledExtractionNeverAdvances() {
    turn("turn1", ConversationTurnStore.Status.COMPLETED);
    when(extractor.extract(anyList())).thenThrow(new java.util.concurrent.CancellationException());
    assertThrows(
        java.util.concurrent.CancellationException.class, () -> service.sleep("s", "p", false));
    assertEquals(0, repository.lastProcessed("s"));
    assertEquals("CANCELLED", repository.history("p", 10).getFirst().status());
  }

  /** 長期記憶の活用で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void schemaFailureInPreviewNeverWritesFailureRun() {
    turn("turn1", ConversationTurnStore.Status.COMPLETED);
    when(extractor.extract(anyList())).thenThrow(new IllegalArgumentException("schema"));
    assertThrows(IllegalArgumentException.class, () -> service.sleep("s", "p", true));
    assertTrue(repository.history("p", 10).isEmpty());
  }

  /** 長期記憶の活用で、保存した情報を、再起動や復元後の処理へ正しく引き継ぐことを確認する。 */
  @Test
  void activityDuringExtractionDoesNotAdvanceCursorOrPersistMemory() {
    turn("turn1", ConversationTurnStore.Status.COMPLETED);
    var active = new java.util.concurrent.atomic.AtomicBoolean();
    when(extractor.extract(anyList()))
        .thenAnswer(
            a -> {
              active.set(true);
              return List.of(LongTermMemoryTest.candidate("Vision first", MemoryScope.PROJECT));
            });
    assertThrows(
        java.util.concurrent.CancellationException.class,
        () -> service.sleep("s", "p", false, active::get));
    assertEquals(0, repository.lastProcessed("s"));
    assertTrue(repository.list("p", 10, 0).isEmpty());
    assertEquals("CANCELLED", repository.history("p", 10).getFirst().status());
  }
}
