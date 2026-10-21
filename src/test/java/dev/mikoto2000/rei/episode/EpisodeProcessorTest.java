package dev.mikoto2000.rei.episode;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyList;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.conversation.ConversationTurnStore;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 経験の記録Processorの結果と状態の契約を検証するテスト。 */
class EpisodeProcessorTest {
  /** 検証に使う保存と検索の窓口。 */
  EpisodeRepository repository = mock(EpisodeRepository.class);

  /** 検証に使うやり取り一覧。 */
  ConversationTurnStore turns = mock(ConversationTurnStore.class);

  /** 検証に使う情報の抽出。 */
  EpisodeExtractor extractor = mock(EpisodeExtractor.class);

  /** {@code episode} の条件を再現するための補助情報を返す。 */
  Episode episode(Episode.Evidence evidence, String run, String speaker) {
    return new Episode(
        "e",
        "p",
        "s",
        "v",
        Instant.EPOCH.toString(),
        Episode.Status.UNVERIFIED,
        "CLI",
        "summary",
        List.of(new Episode.Claim("reason", "isolation", evidence, run, speaker)),
        .8);
  }

  /** 過去の経験の活用で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void rejectsFabricatedSourceAndDoesNotAdvanceCheckpoint() {
    var batch =
        List.of(
            new ConversationTurnStore.Turn(
                "r",
                "choose CLI",
                ConversationTurnStore.Status.COMPLETED,
                "proposal",
                Instant.EPOCH));
    when(extractor.extract(eq("s"), eq("p"), eq(batch), any(), any()))
        .thenReturn(List.of(episode(Episode.Evidence.UNVERIFIED, "invented", "assistant")));
    assertThrows(
        IllegalArgumentException.class,
        () -> new EpisodeProcessor(repository, extractor).process("s", "p", 0, 1, batch, null));
    verify(repository, never()).saveBatch(anyString(), anyLong(), anyLong(), anyList());
  }

  /** 提案一覧を保持することを確認する。 */
  @Test
  void preservesProposalsAndCommitsEmptyExtractionForSmallTalk() {
    var batch =
        List.of(
            new ConversationTurnStore.Turn(
                "r", "hello", ConversationTurnStore.Status.COMPLETED, "hello", Instant.EPOCH));
    when(extractor.extract(eq("s"), eq("p"), eq(batch), any(), any())).thenReturn(List.of());
    new EpisodeProcessor(repository, extractor).process("s", "p", 0, 1, batch, null);
    verify(repository).saveBatch("s", 0, 1, List.of());
  }

  /** 過去の経験の活用で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void assistantCannotBecomeUserDecision() {
    assertThrows(
        IllegalArgumentException.class,
        () -> episode(Episode.Evidence.USER_EXPLICIT, "r", "assistant"));
    assertThrows(
        IllegalArgumentException.class,
        () -> episode(Episode.Evidence.TOOL_OBSERVED, "r", "assistant"));
  }

  /** 複数やり取り一覧を受け付けられることを確認する。 */
  @Test
  void acceptsMultipleTurnsAndKeepsAlternativeAndUnknown() {
    var batch =
        List.of(
            new ConversationTurnStore.Turn(
                "r",
                "choose CLI",
                ConversationTurnStore.Status.COMPLETED,
                "proposal",
                Instant.EPOCH),
            new ConversationTurnStore.Turn(
                "r2",
                "follow up",
                ConversationTurnStore.Status.COMPLETED,
                "pending",
                Instant.EPOCH));
    var e = episode(Episode.Evidence.MODEL_PROPOSAL, "r", "assistant");
    when(extractor.extract(eq("s"), eq("p"), eq(batch), any(), any())).thenReturn(List.of(e));
    new EpisodeProcessor(repository, extractor).process("s", "p", 0, 2, batch, null);
    verify(repository).saveBatch("s", 0, 2, List.of(e));
  }

  /** 過去の経験の活用で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void modelProposalCannotMarkAnEpisodeCompleted() {
    var batch =
        List.of(
            new ConversationTurnStore.Turn(
                "r",
                "consider CLI",
                ConversationTurnStore.Status.COMPLETED,
                "proposal",
                Instant.EPOCH));
    var proposed = episode(Episode.Evidence.MODEL_PROPOSAL, "r", "assistant");
    var completed =
        new Episode(
            proposed.id(),
            proposed.projectId(),
            proposed.sessionId(),
            proposed.revision(),
            proposed.occurredAt(),
            Episode.Status.COMPLETED,
            proposed.title(),
            proposed.summary(),
            proposed.claims(),
            proposed.confidence());
    when(extractor.extract(eq("s"), eq("p"), eq(batch), any(), any()))
        .thenReturn(List.of(completed));
    assertThrows(
        IllegalArgumentException.class,
        () -> new EpisodeProcessor(repository, extractor).process("s", "p", 0, 1, batch, null));
  }
}
