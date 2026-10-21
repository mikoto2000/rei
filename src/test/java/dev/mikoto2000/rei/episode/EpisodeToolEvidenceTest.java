package dev.mikoto2000.rei.episode;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.anyList;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.conversation.ConversationTurnStore;
import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventType;
import dev.mikoto2000.rei.event.ProjectAgentEventStore;
import dev.mikoto2000.rei.event.ToolCompletedPayload;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 経験の記録ツール根拠の結果と状態の契約を検証するテスト。 */
class EpisodeToolEvidenceTest {
  /** 過去の経験の活用で、条件が変わっても、引き継ぐべき情報や状態を保持することを確認する。 */
  @Test
  void toolEvidenceRequiresExactRetainedEventAndRun() {
    var repository = mock(EpisodeRepository.class);
    var extractor = mock(EpisodeExtractor.class);
    var events = mock(ProjectAgentEventStore.class);
    var turn =
        new ConversationTurnStore.Turn(
            "r", "test CLI", ConversationTurnStore.Status.COMPLETED, "passed", Instant.EPOCH);
    var claim =
        new Episode.Claim(
            "result", "3 tests passed", Episode.Evidence.TOOL_OBSERVED, "r", "tool", "event1");
    var episode =
        new Episode(
            "e",
            "p",
            "s",
            "v",
            Instant.EPOCH.toString(),
            Episode.Status.COMPLETED,
            "CLI tests",
            "Tests passed",
            List.of(claim),
            1);
    var event =
        new AgentEvent(
            "event1",
            1,
            Instant.EPOCH,
            AgentEventType.TOOL_COMPLETED,
            1,
            "s",
            "r",
            "r",
            null,
            null,
            new ToolCompletedPayload("call", "test", 1, "3 tests passed", null, null, null, null),
            "p");
    when(events.recent("p", 1000)).thenReturn(List.of(event));
    when(events.findEvent("p", "event1")).thenReturn(Optional.of(event));
    when(extractor.extract(eq("s"), eq("p"), anyList(), anyList(), isNull()))
        .thenReturn(List.of(episode));
    var processor = new EpisodeProcessor(repository, extractor);
    processor.setEvents(events);
    processor.process("s", "p", 0, 1, List.of(turn), null);
    verify(repository).saveBatch("s", 0, 1, List.of(episode));
    when(events.recent("p", 1000)).thenReturn(List.of());
    when(events.findEvent("p", "event1")).thenReturn(Optional.empty());
    assertThrows(
        IllegalArgumentException.class,
        () -> processor.process("s", "p", 0, 1, List.of(turn), null));
  }
}
