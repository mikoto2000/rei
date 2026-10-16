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

class EpisodeSleepTest {
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
    verifyNoInteractions(turns, processor, repository);
  }

  @Test
  void rangeIsBoundedAndCompletedTurnsOnly() {
    var store = ConversationTurnStore.inMemory();
    assertEquals(List.of(), store.readRange("s", 0, 50));
    assertThrows(IllegalArgumentException.class, () -> store.readRange("s", 0, 500));
  }

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
