package dev.mikoto2000.rei.interest;

import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import dev.mikoto2000.rei.search.SearchKnowledgeResult;
import dev.mikoto2000.rei.search.SearchKnowledgeService;
import dev.mikoto2000.rei.websearch.WebSearchPage;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class InterestDiscoveryJob {

  private final ConversationInterestService conversationInterestService;
  private final SearchKnowledgeService searchKnowledgeService;
  private final InterestUpdateService interestUpdateService;
  private final InterestProperties properties;

  @Scheduled(cron = "${rei.interest.cron:0 0 7 * * *}")
  public void run() {
    discover(false);
  }

  public int discoverNow() {
    return discover(true);
  }

  private int discover(boolean force) {
    if (!force && !properties.isEnabled()) {
      return 0;
    }

    int savedCount = 0;

    for (InterestTopicCandidate candidate : conversationInterestService.discoverCandidates()) {
      if (interestUpdateService.existsBySearchQuery(candidate.searchQuery())) {
        continue;
      }

      try {
        SearchKnowledgeResult result = searchKnowledgeService.search(
            candidate.searchQuery(),
            properties.getVectorTopK(),
            properties.getWebTopK(),
            null,
            null);
        List<WebSearchPage> pages = result.webContext().allResults();
        if (pages.isEmpty()) {
          continue;
        }
        interestUpdateService.save(
            candidate.topic(),
            candidate.reason(),
            candidate.searchQuery(),
            summarize(pages),
            pages.stream().map(WebSearchPage::url).toList());
        savedCount++;
      } catch (Exception e) {
        // 定期ジョブ全体を止めない
      }
    }
    return savedCount;
  }

  private String summarize(List<WebSearchPage> pages) {
    return pages.stream()
        .limit(2)
        .map(WebSearchPage::title)
        .reduce((left, right) -> left + " / " + right)
        .orElse("関連情報を検出");
  }
}
