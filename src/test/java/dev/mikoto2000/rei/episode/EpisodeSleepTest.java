package dev.mikoto2000.rei.episode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.conversation.ConversationTurnStore;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 経験の記録待機の結果と状態の契約を検証するテスト。 */
class EpisodeSleepTest {
  /** 過去の経験の活用で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void disabledDoesNotReadHistoryOrCallModel() {
    var turns = mock(ConversationTurnStore.class);
    var processor = mock(EpisodeProcessor.class);
    var repository = mock(EpisodeRepository.class);
    new EpisodeSleepService(
            repository,
            turns,
            processor,
            new EpisodeProperties(false),
            new dev.mikoto2000.rei.memory.configuration.MemoryProperties(
                true, 0, 0, 0, 0, 0, 0, null))
        .process("s", "p", null, () -> false);
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(turns, processor, repository);
  }

  /** 過去の経験の活用で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void rangeIsBoundedAndCompletedTurnsOnly() {
    var store = ConversationTurnStore.inMemory();
    assertEquals(List.of(), store.readRange("s", 0, 50));
    assertThrows(IllegalArgumentException.class, () -> store.readRange("s", 0, 500));
  }

  /** 過去の経験の活用で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void failedAttemptIsRetainedAsTerminalEvidenceWithoutInventingSuccess() {
    var turns = mock(ConversationTurnStore.class);
    var processor = mock(EpisodeProcessor.class);
    var repository = mock(EpisodeRepository.class);
    var failed =
        new ConversationTurnStore.Turn(
            "r",
            "fix CLI",
            ConversationTurnStore.Status.FAILED,
            "test failed",
            java.time.Instant.EPOCH);
    when(repository.acquire(eq("s"), anyString(), anyLong())).thenReturn(true);
    when(turns.readRange("s", 0, 50)).thenReturn(List.of(failed));
    new EpisodeSleepService(
            repository,
            turns,
            processor,
            new EpisodeProperties(true),
            new dev.mikoto2000.rei.memory.configuration.MemoryProperties(
                true, 0, 0, 0, 0, 0, 0, null))
        .process("s", "p", null, () -> false);
    verify(processor).process("s", "p", 0, 1, List.of(failed), null);
  }
}
