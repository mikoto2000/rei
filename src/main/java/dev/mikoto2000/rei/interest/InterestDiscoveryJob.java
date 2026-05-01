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

    // 過去クエリを取得して LLM コンテキストに渡す（A案）
    List<String> pastQueries = interestUpdateService.listRecentSearchQueries(properties.getPastQueryLookbackDays());

    progressListener.accept("候補トピックを抽出しています...");
    List<InterestTopicCandidate> candidates = conversationInterestService.discoverCandidates(pastQueries);
    progressListener.accept("候補トピックを " + candidates.size() + " 件抽出しました");
    boolean usedFallbackCandidates = false;
    if (candidates.isEmpty()) {
      progressListener.accept("候補が 0 件だったため、条件を広げて再抽出しています...");
      candidates = conversationInterestService.discoverFallbackCandidates(pastQueries);
      progressListener.accept("再抽出で候補トピックを " + candidates.size() + " 件抽出しました");
      usedFallbackCandidates = true;
    }

    java.util.ArrayList<InterestUpdate> savedUpdates = processCandidates(candidates, progressListener);
    if (savedUpdates.isEmpty() && !usedFallbackCandidates) {
      progressListener.accept("更新が 0 件だったため、条件を広げて再抽出しています...");
      List<InterestTopicCandidate> fallbackCandidates = conversationInterestService.discoverFallbackCandidates(pastQueries);
      progressListener.accept("再抽出で候補トピックを " + fallbackCandidates.size() + " 件抽出しました");
      savedUpdates = processCandidates(fallbackCandidates, progressListener);
    }
    return savedUpdates;
  }

  private java.util.ArrayList<InterestUpdate> processCandidates(
      List<InterestTopicCandidate> candidates,
      Consumer<String> progressListener) {
    java.util.ArrayList<InterestUpdate> savedUpdates = new java.util.ArrayList<>();
    int total = candidates.size();
    for (int i = 0; i < total; i++) {
      InterestTopicCandidate candidate = candidates.get(i);
      String prefix = (i + 1) + "/" + total + " 件目";

      String skipReason = skipReason(candidate);
      if (skipReason != null) {
        progressListener.accept(prefix + " はスキップ（" + skipReason + "）: " + candidate.topic());
        continue;
      }

      try {
        progressListener.accept(prefix + " を検索しています: " + candidate.topic());
        SearchKnowledgeResult result = searchKnowledgeService.search(
            candidate.searchQuery(),
            properties.getVectorTopK(),
            properties.getWebTopK(),
            null,
            null);
        List<WebSearchPage> pages = result.webContext().allResults();
        if (pages.isEmpty()) {
          progressListener.accept(prefix + " は結果なし: " + candidate.topic());
          continue;
        }
        InterestUpdate saved = interestUpdateService.save(
            candidate.topic(),
            candidate.reason(),
            candidate.searchQuery(),
            summarize(pages),
            pages.stream().map(WebSearchPage::url).toList());
        savedUpdates.add(saved);
        progressListener.accept(prefix + " を追加しました: " + candidate.topic());
      } catch (Exception e) {
        // 定期ジョブ全体を止めない
        progressListener.accept(prefix + " の処理に失敗しました: " + candidate.topic());
      }
    }
    return savedUpdates;
  }

  /**
   * スキップすべき理由を返す。スキップ不要なら null を返す。
   * 優先順: 1. トピック頻度制限（C案）、2. 検索クエリ重複
   */
  private String skipReason(InterestTopicCandidate candidate) {
    if (interestUpdateService.existsByTopicWithinHours(candidate.topic(), properties.getTopicUpdateIntervalHours())) {
      return "頻度制限内";
    }
    if (interestUpdateService.existsBySearchQuery(candidate.searchQuery())) {
      return "既存クエリ";
    }
    return null;
  }

  private String summarize(List<WebSearchPage> pages) {
    return pages.stream()
        .limit(2)
        .map(WebSearchPage::title)
        .reduce((left, right) -> left + " / " + right)
        .orElse("関連情報を検出");
  }
}
