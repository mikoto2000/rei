package dev.mikoto2000.rei.episode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.conversation.ConversationHistorySearchService;
import dev.mikoto2000.rei.conversation.ConversationTurnStore;
import dev.mikoto2000.rei.conversation.HistorySearchRequest;
import dev.mikoto2000.rei.conversation.HistorySearchScope;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 経験の記録ベクトルによる関連情報の取得の結果と状態の契約を検証するテスト。 */
class EpisodeDenseRetrievalTest {
  /** 検証に使うプロジェクト。 */
  final String project = "11111111-1111-1111-1111-111111111111";

  /** 検証に使うリポジトリ。 */
  EpisodeRepository repo = mock(EpisodeRepository.class);

  /** 検証に使うベクトルによる。 */
  EpisodeDenseIndex dense = mock(EpisodeDenseIndex.class);

  /** 検証に使うやり取り一覧。 */
  ConversationTurnStore turns = mock(ConversationTurnStore.class);

  /** 検証に使うサービス。 */
  EpisodeSearchService service =
      new EpisodeSearchService(
          repo,
          mock(dev.mikoto2000.rei.memory.service.MemoryRepository.class),
          mock(ConversationHistorySearchService.class),
          turns,
          mock(dev.mikoto2000.rei.workcontext.WorkContextService.class));

  /** 検証で使用する要求を、指定された条件に合わせて用意する。 */
  HistorySearchRequest request() {
    return new HistorySearchRequest(
        "semantic synonym",
        project,
        null,
        HistorySearchScope.CURRENT_PROJECT_ONLY,
        "all",
        null,
        null,
        null,
        8);
  }

  /** 検証条件に必要な値を受け取り、EpisodeDenseRetrievalTestを用意する。 */
  EpisodeDenseRetrievalTest() {
    service.setDense(dense);
  }

  /** 実行を停止することを確認する。 */
  @Test
  void providerFailureFallsBackButBudgetExhaustionStillStopsTheRun() {
    when(dense.search(anyString(), eq(project), anyInt(), any()))
        .thenThrow(new IllegalStateException("provider unavailable"));
    assertTrue(service.search(request(), "known topic").isEmpty());
    var stopped =
        new dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException(
            dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException.Reason
                .TOKEN_BUDGET_EXCEEDED);
    when(dense.search(anyString(), eq(project), anyInt(), any())).thenThrow(stopped);
    assertSame(
        stopped,
        assertThrows(
            dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException.class,
            () -> service.search(request(), "known topic")));
  }

  /** 過去の経験の活用で、再送や重複を、受付と実行の規則に従って扱うことを確認する。 */
  @Test
  void denseAndLexicalDuplicatesFuseIntoOneEvidenceCheckedCandidate() {
    var e =
        new Episode(
            "e",
            project,
            "s",
            "v",
            "2026-10-11T00:00:00Z",
            Episode.Status.UNVERIFIED,
            "design",
            "summary",
            List.of(
                new Episode.Claim(
                    "reason", "proposal", Episode.Evidence.MODEL_PROPOSAL, "r", "assistant")),
            .5);
    when(repo.search(anyString(), eq(project), anyInt(), any())).thenReturn(List.of(e));
    when(dense.search(anyString(), eq(project), anyInt(), any())).thenReturn(List.of(e));
    when(turns.findRun("s", "r"))
        .thenReturn(
            Optional.of(
                new ConversationTurnStore.Turn(
                    "r",
                    "request",
                    ConversationTurnStore.Status.COMPLETED,
                    "proposal",
                    java.time.Instant.EPOCH)));
    assertEquals(1, service.search(request(), "known topic").size());
    when(turns.findRun("s", "r")).thenReturn(Optional.empty());
    assertTrue(service.search(request(), "known topic").isEmpty());
  }

  /** 過去の経験の活用で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void disabledMemoryNeverCallsDenseOrLexicalProviders() {
    service.setMemoryProperties(
        new dev.mikoto2000.rei.memory.configuration.MemoryProperties(
            false, 0, 0, 0, 0, 0, 0, null));
    service.search(request(), "known topic");
    verifyNoInteractions(repo, dense, turns);
  }
}
