package dev.mikoto2000.rei.interest;

import java.util.List;
import java.util.function.Consumer;

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
    discover(false, message -> {
    });
  }

  public List<InterestUpdate> discoverNow() {
    return discoverNow(message -> {
    });
  }

  public List<InterestUpdate> discoverNow(Consumer<String> progressListener) {
    return discover(true, progressListener);
  }

  private List<InterestUpdate> discover(boolean force, Consumer<String> progressListener) {
    if (!force && !properties.isEnabled()) {
      return List.of();
    }

    java.util.ArrayList<InterestUpdate> savedUpdates = new java.util.ArrayList<>();
    progressListener.accept("候補トピックを抽出しています...");
    List<InterestTopicCandidate> candidates = conversationInterestService.discoverCandidates();
    progressListener.accept("候補トピックを " + candidates.size() + " 件抽出しました");

    for (int i = 0; i < candidates.size(); i++) {
      InterestTopicCandidate candidate = candidates.get(i);
      if (interestUpdateService.existsBySearchQuery(candidate.searchQuery())) {
        progressListener.accept((i + 1) + "/" + candidates.size() + " 件目は既存トピックのためスキップ: " + candidate.topic());
        continue;
      }

      try {
        progressListener.accept((i + 1) + "/" + candidates.size() + " 件目を検索しています: " + candidate.topic());
        SearchKnowledgeResult result = searchKnowledgeService.search(
            candidate.searchQuery(),
            properties.getVectorTopK(),
            properties.getWebTopK(),
            null,
            null);
        List<WebSearchPage> pages = result.webContext().allResults();
        if (pages.isEmpty()) {
          progressListener.accept((i + 1) + "/" + candidates.size() + " 件目は結果なし: " + candidate.topic());
          continue;
        }
        InterestUpdate saved = interestUpdateService.save(
            candidate.topic(),
            candidate.reason(),
            candidate.searchQuery(),
            summarize(pages),
            pages.stream().map(WebSearchPage::url).toList());
        savedUpdates.add(saved);
        progressListener.accept((i + 1) + "/" + candidates.size() + " 件目を追加しました: " + candidate.topic());
      } catch (Exception e) {
        // 定期ジョブ全体を止めない
        progressListener.accept((i + 1) + "/" + candidates.size() + " 件目の処理に失敗しました: " + candidate.topic());
      }
    }
    return savedUpdates;
  }

  private String summarize(List<WebSearchPage> pages) {
    return pages.stream()
        .limit(2)
        .map(WebSearchPage::title)
        .reduce((left, right) -> left + " / " + right)
        .orElse("関連情報を検出");
  }
}
