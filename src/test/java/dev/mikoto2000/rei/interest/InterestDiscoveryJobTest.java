package dev.mikoto2000.rei.interest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import dev.mikoto2000.rei.search.SearchKnowledgeResult;
import dev.mikoto2000.rei.search.SearchKnowledgeService;
import dev.mikoto2000.rei.websearch.WebSearchContext;
import dev.mikoto2000.rei.websearch.WebSearchPage;

class InterestDiscoveryJobTest {

  @TempDir
  java.nio.file.Path tempDir;

  @Test
  void runSavesSearchResultsAsInterestUpdates() throws Exception {
    ConversationInterestService conversationInterestService = org.mockito.Mockito.mock(ConversationInterestService.class);
    SearchKnowledgeService searchKnowledgeService = org.mockito.Mockito.mock(SearchKnowledgeService.class);
    InterestUpdateService interestUpdateService = new InterestUpdateService(
        new DriverManagerDataSource("jdbc:sqlite:" + tempDir.resolve("interest.db")));
    InterestProperties properties = new InterestProperties();
    properties.setEnabled(true);
    InterestDiscoveryJob job = new InterestDiscoveryJob(conversationInterestService, searchKnowledgeService, interestUpdateService, properties);

    when(conversationInterestService.discoverCandidates()).thenReturn(List.of(
        new InterestTopicCandidate(
            "Neovim 開発環境",
            "繰り返し話題になっている",
            "Neovim devcontainer best practices",
            0.82)));
    when(searchKnowledgeService.search("Neovim devcontainer best practices", 3, 5, null, null)).thenReturn(
        new SearchKnowledgeResult(
            "Neovim devcontainer best practices",
            List.of(),
            WebSearchContext.primaryOnly(List.of(
                new WebSearchPage("Neovim docs", "https://example.com/nvim", "snippet", "2026-04-18", "content"))),
            null));

    job.run();

    List<InterestUpdate> updates = interestUpdateService.listRecent(24);
    assertEquals(1, updates.size());
    assertEquals("Neovim 開発環境", updates.getFirst().topic());
    assertEquals(List.of("https://example.com/nvim"), updates.getFirst().sourceUrls());
  }
}
