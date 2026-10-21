package dev.mikoto2000.rei.interest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.search.SearchKnowledgeResult;
import dev.mikoto2000.rei.search.SearchKnowledgeService;
import dev.mikoto2000.rei.websearch.WebSearchContext;
import dev.mikoto2000.rei.websearch.WebSearchPage;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 関心利用先の検出実行する作業の結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class InterestDiscoveryJobTest {

  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir java.nio.file.Path tempDir;

  /** 関心の管理で、検索条件に応じて、必要な候補と根拠を選ぶことを確認する。 */
  @Test
  void runSavesSearchResultsAsInterestUpdates() throws Exception {
    ConversationInterestService conversationInterestService =
        org.mockito.Mockito.mock(ConversationInterestService.class);
    SearchKnowledgeService searchKnowledgeService =
        org.mockito.Mockito.mock(SearchKnowledgeService.class);
    InterestUpdateService interestUpdateService =
        new InterestUpdateService(
            new DriverManagerDataSource("jdbc:sqlite:" + tempDir.resolve("interest.db")));
    InterestProperties properties = new InterestProperties();
    properties.setEnabled(true);
    InterestDiscoveryJob job =
        new InterestDiscoveryJob(
            conversationInterestService, searchKnowledgeService, interestUpdateService, properties);

    when(conversationInterestService.discoverCandidates(anyList()))
        .thenReturn(
            List.of(
                new InterestTopicCandidate(
                    "Neovim 開発環境", "繰り返し話題になっている", "Neovim devcontainer best practices", 0.82)));
    when(searchKnowledgeService.search("Neovim devcontainer best practices", 3, 5, null, null))
        .thenReturn(
            new SearchKnowledgeResult(
                "Neovim devcontainer best practices",
                List.of(),
                WebSearchContext.primaryOnly(
                    List.of(
                        new WebSearchPage(
                            "Neovim docs",
                            "https://example.com/nvim",
                            "snippet",
                            "2026-04-18",
                            "content"))),
                null));

    job.run();

    List<InterestUpdate> updates = interestUpdateService.listRecent(24);
    assertEquals(1, updates.size());
    assertEquals("Neovim 開発環境", updates.getFirst().topic());
    assertEquals(List.of("https://example.com/nvim"), updates.getFirst().sourceUrls());
  }

  /** 関心の管理で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void discoverNowSkipsTopicWhenWithinFrequencyLimit() throws Exception {
    ConversationInterestService conversationInterestService =
        org.mockito.Mockito.mock(ConversationInterestService.class);
    SearchKnowledgeService searchKnowledgeService =
        org.mockito.Mockito.mock(SearchKnowledgeService.class);
    InterestUpdateService interestUpdateService =
        new InterestUpdateService(
            new DriverManagerDataSource("jdbc:sqlite:" + tempDir.resolve("freq-limit.db")));
    InterestProperties properties = new InterestProperties();
    properties.setEnabled(true);
    InterestDiscoveryJob job =
        new InterestDiscoveryJob(
            conversationInterestService, searchKnowledgeService, interestUpdateService, properties);

    // 頻度制限内に同一トピックのレコードを事前に保存
    interestUpdateService.saveWithCreatedAt(
        "Neovim 開発環境",
        "reason",
        "existing-query",
        "summary",
        List.of(),
        OffsetDateTime.now(ZoneOffset.UTC).minusHours(1));

    when(conversationInterestService.discoverCandidates(anyList()))
        .thenReturn(List.of(new InterestTopicCandidate("Neovim 開発環境", "reason", "new-query", 0.9)));

    job.discoverNow();

    // 頻度制限内のため search が呼ばれないこと
    verify(searchKnowledgeService, never()).search(anyString(), anyInt(), anyInt(), any(), any());
  }

  /** 関心の管理で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void discoverNowPassesPastQueriesToDiscoverCandidates() throws Exception {
    ConversationInterestService conversationInterestService =
        org.mockito.Mockito.mock(ConversationInterestService.class);
    SearchKnowledgeService searchKnowledgeService =
        org.mockito.Mockito.mock(SearchKnowledgeService.class);
    InterestUpdateService interestUpdateService =
        new InterestUpdateService(
            new DriverManagerDataSource("jdbc:sqlite:" + tempDir.resolve("past-queries.db")));
    InterestProperties properties = new InterestProperties();
    properties.setEnabled(true);
    InterestDiscoveryJob job =
        new InterestDiscoveryJob(
            conversationInterestService, searchKnowledgeService, interestUpdateService, properties);

    // 過去クエリを事前に保存
    interestUpdateService.saveWithCreatedAt(
        "Topic A",
        "reason",
        "past-query-1",
        "summary",
        List.of(),
        OffsetDateTime.now(ZoneOffset.UTC).minusDays(1));

    when(conversationInterestService.discoverCandidates(anyList())).thenReturn(List.of());

    job.discoverNow();

    // listRecentSearchQueries の結果が discoverCandidates に渡されること
    verify(conversationInterestService).discoverCandidates(eq(List.of("past-query-1")));
  }

  /** 関心の管理で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void discoverNowBroadensCandidatesWhenInitialDiscoveryFindsNothing() throws Exception {
    ConversationInterestService conversationInterestService =
        org.mockito.Mockito.mock(ConversationInterestService.class);
    SearchKnowledgeService searchKnowledgeService =
        org.mockito.Mockito.mock(SearchKnowledgeService.class);
    InterestUpdateService interestUpdateService =
        new InterestUpdateService(
            new DriverManagerDataSource(
                "jdbc:sqlite:" + tempDir.resolve("fallback-candidates.db")));
    InterestProperties properties = new InterestProperties();
    properties.setEnabled(true);
    InterestDiscoveryJob job =
        new InterestDiscoveryJob(
            conversationInterestService, searchKnowledgeService, interestUpdateService, properties);

    when(conversationInterestService.discoverCandidates(anyList())).thenReturn(List.of());
    when(conversationInterestService.discoverFallbackCandidates(anyList()))
        .thenReturn(
            List.of(
                new InterestTopicCandidate(
                    "Neovim 開発環境", "繰り返し話題になっている", "Neovim devcontainer best practices", 0.45)));
    when(searchKnowledgeService.search("Neovim devcontainer best practices", 3, 5, null, null))
        .thenReturn(
            new SearchKnowledgeResult(
                "Neovim devcontainer best practices",
                List.of(),
                WebSearchContext.primaryOnly(
                    List.of(
                        new WebSearchPage(
                            "Neovim docs",
                            "https://example.com/nvim",
                            "snippet",
                            "2026-04-18",
                            "content"))),
                null));

    List<InterestUpdate> updates = job.discoverNow();

    assertEquals(1, updates.size());
    verify(conversationInterestService).discoverFallbackCandidates(eq(List.of()));
  }

  /** 関心の管理で、検索条件に応じて、必要な候補と根拠を選ぶことを確認する。 */
  @Test
  void discoverNowBroadensCandidatesWhenInitialCandidatesHaveNoSearchResults() throws Exception {
    ConversationInterestService conversationInterestService =
        org.mockito.Mockito.mock(ConversationInterestService.class);
    SearchKnowledgeService searchKnowledgeService =
        org.mockito.Mockito.mock(SearchKnowledgeService.class);
    InterestUpdateService interestUpdateService =
        new InterestUpdateService(
            new DriverManagerDataSource(
                "jdbc:sqlite:" + tempDir.resolve("fallback-no-results.db")));
    InterestProperties properties = new InterestProperties();
    properties.setEnabled(true);
    InterestDiscoveryJob job =
        new InterestDiscoveryJob(
            conversationInterestService, searchKnowledgeService, interestUpdateService, properties);

    when(conversationInterestService.discoverCandidates(anyList()))
        .thenReturn(List.of(new InterestTopicCandidate("Candidate A", "reason", "query-a", 0.8)));
    when(conversationInterestService.discoverFallbackCandidates(anyList()))
        .thenReturn(List.of(new InterestTopicCandidate("Candidate B", "reason", "query-b", 0.45)));
    when(searchKnowledgeService.search("query-a", 3, 5, null, null))
        .thenReturn(
            new SearchKnowledgeResult(
                "query-a", List.of(), WebSearchContext.primaryOnly(List.of()), null));
    when(searchKnowledgeService.search("query-b", 3, 5, null, null))
        .thenReturn(
            new SearchKnowledgeResult(
                "query-b",
                List.of(),
                WebSearchContext.primaryOnly(
                    List.of(
                        new WebSearchPage(
                            "Fallback page",
                            "https://example.com/fallback",
                            "snippet",
                            "2026-04-18",
                            "content"))),
                null));

    List<InterestUpdate> updates = job.discoverNow();

    assertEquals(1, updates.size());
    assertEquals("Candidate B", updates.getFirst().topic());
    verify(conversationInterestService).discoverFallbackCandidates(eq(List.of()));
  }
}
