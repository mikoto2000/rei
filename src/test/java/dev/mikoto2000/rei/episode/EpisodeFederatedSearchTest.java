package dev.mikoto2000.rei.episode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.conversation.ConversationHistorySearchService;
import dev.mikoto2000.rei.conversation.ConversationSearchResult;
import dev.mikoto2000.rei.conversation.ConversationTurnStore;
import dev.mikoto2000.rei.conversation.HistoryRetrievalPolicy;
import dev.mikoto2000.rei.conversation.HistorySearchRequest;
import dev.mikoto2000.rei.conversation.HistorySearchScope;
import dev.mikoto2000.rei.memory.service.MemoryRepository;
import dev.mikoto2000.rei.workcontext.WorkContextService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 経験の記録Federated検索の結果と状態の契約を検証するテスト。 */
class EpisodeFederatedSearchTest {
  /** 検証に使うプロジェクト。 */
  final String project = "11111111-1111-1111-1111-111111111111";

  /** 検証に使う経験の記録一覧。 */
  EpisodeRepository episodes = mock(EpisodeRepository.class);

  /** 検証に使う記憶。 */
  MemoryRepository memory = mock(MemoryRepository.class);

  /** 検証に使う履歴。 */
  ConversationHistorySearchService history = mock(ConversationHistorySearchService.class);

  /** 検証に使うやり取り一覧。 */
  ConversationTurnStore turns = mock(ConversationTurnStore.class);

  /** 検証に使う作業。 */
  WorkContextService work = mock(WorkContextService.class);

  /** 検証に使うサービス。 */
  EpisodeSearchService service = new EpisodeSearchService(episodes, memory, history, turns, work);

  /** 検証で使用する要求を、指定された条件に合わせて用意する。 */
  HistorySearchRequest request() {
    return new HistorySearchRequest(
        "CLI", project, null, HistorySearchScope.CURRENT_PROJECT_ONLY, "all", null, null, null, 8);
  }

  /** 各検証を独立した状態で始められるよう、依存先と検証データを準備する。 */
  @BeforeEach
  void setup() {
    when(work.current(project)).thenReturn(Optional.empty());
  }

  /** {@code raw} の条件を再現するための補助情報を返す。 */
  ConversationSearchResult raw() {
    return new ConversationSearchResult(
        "s",
        "chat",
        "user",
        Instant.EPOCH.toString(),
        "CLI pending",
        "CLI pending",
        project,
        "Project A",
        HistoryRetrievalPolicy.LOCAL_BOUNDARY,
        1);
  }

  /** 過去の経験の活用で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void rawConversationsAreSearchableWithoutAnySleepOrEpisode() {
    when(history.search(any(HistorySearchRequest.class))).thenReturn(List.of(raw()));
    var hits = service.search(request(), null);
    assertEquals("conversation", hits.getFirst().kind());
    assertEquals("CLI pending", hits.getFirst().summary());
    verify(episodes, never()).checkpoint(anyString());
  }

  /** 過去の経験の活用で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void candidatesOmitClaimBodiesUntilDetailIsRequested() {
    var episode =
        new Episode(
            "e",
            project,
            "s",
            "v",
            Instant.EPOCH.toString(),
            Episode.Status.UNVERIFIED,
            "CLI",
            "design proposal",
            List.of(
                new Episode.Claim(
                    "reason",
                    "detailed isolation reasoning",
                    Episode.Evidence.MODEL_PROPOSAL,
                    "r",
                    "assistant")),
            .8);
    when(episodes.search(anyString(), eq(project), anyInt(), any(EpisodeTimeRange.class)))
        .thenReturn(List.of(episode));
    when(episodes.revisions("e", project)).thenReturn(List.of(episode));
    when(turns.findRun("s", "r"))
        .thenReturn(
            Optional.of(
                new ConversationTurnStore.Turn(
                    "r",
                    "CLI",
                    ConversationTurnStore.Status.COMPLETED,
                    "proposal",
                    Instant.EPOCH)));
    assertFalse(
        service.search(request(), null).toString().contains("detailed isolation reasoning"));
    assertTrue(service.detail("e", project).toString().contains("detailed isolation reasoning"));
  }

  /** 過去の経験の活用で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void disablingMemoryLeavesRawHistoryButNeverReadsSavedMemory() {
    when(history.search(any(HistorySearchRequest.class))).thenReturn(List.of(raw()));
    service.setMemoryProperties(
        new dev.mikoto2000.rei.memory.configuration.MemoryProperties(
            false, 0, 0, 0, 0, 0, 0, null));
    assertEquals("conversation", service.search(request(), null).getFirst().kind());
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(episodes, memory, turns);
  }

  /** 実際の直近会話話題を利用することを確認する。 */
  @Test
  void ambiguousQuestionUsesActualRecentSessionTopic() {
    var old =
        new ConversationTurnStore.Turn(
            "previous",
            "CLI independent process",
            ConversationTurnStore.Status.COMPLETED,
            "proposal",
            Instant.EPOCH);
    var current =
        new ConversationTurnStore.Turn(
            "current",
            "why that decision",
            ConversationTurnStore.Status.RUNNING,
            null,
            Instant.EPOCH.plusSeconds(1));
    when(turns.turnCount("s")).thenReturn(2L);
    when(turns.readRange("s", 0, 3)).thenReturn(List.of(old, current));
    var request =
        new HistorySearchRequest(
            "why that decision",
            project,
            null,
            HistorySearchScope.CURRENT_PROJECT_ONLY,
            "all",
            null,
            null,
            null,
            8);
    try (var scope =
        dev.mikoto2000.rei.core.chat.AgentRunScope.open(
            new dev.mikoto2000.rei.core.chat.AgentRunContext(
                "current", "s", java.nio.file.Path.of("."), project))) {
      service.search(request, null);
    }
    verify(history, atLeastOnce()).search(argThat(r -> r.query().contains("CLI")));
  }
}
