package dev.mikoto2000.rei.interest;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 会話関心のユースケースを実現する。 */
@Service
@RequiredArgsConstructor
public class ConversationInterestService {

  /** 代替手段最大話題一覧MULTIPLIER。 */
  private static final int FALLBACK_MAX_TOPICS_MULTIPLIER = 2;

  /** 代替手段採用する評価値の下限差分。 */
  private static final double FALLBACK_MIN_SCORE_DELTA = 0.2d;

  /** 会話履歴サービス。 */
  private final ConversationHistoryService conversationHistoryService;

  /** 関心話題情報の抽出。 */
  private final InterestTopicExtractor interestTopicExtractor;

  /** この機能の動作設定。 */
  private final InterestProperties properties;

  /** 関心の管理で用いる利用先の検出候補一覧を返す。 */
  public List<InterestTopicCandidate> discoverCandidates() {
    return discoverCandidates(List.of());
  }

  /** 関心の管理で用いる利用先の検出候補一覧を返す。 */
  public List<InterestTopicCandidate> discoverCandidates(List<String> pastQueries) {
    return discoverCandidates(pastQueries, properties.getMaxTopics(), properties.getMinScore());
  }

  /** 関心の管理で用いる利用先の検出代替手段候補一覧を返す。 */
  public List<InterestTopicCandidate> discoverFallbackCandidates(List<String> pastQueries) {
    int broadenedMaxTopics =
        Math.max(
            properties.getMaxTopics() * FALLBACK_MAX_TOPICS_MULTIPLIER, properties.getMaxTopics());
    double broadenedMinScore = Math.max(0.0d, properties.getMinScore() - FALLBACK_MIN_SCORE_DELTA);
    return discoverCandidates(pastQueries, broadenedMaxTopics, broadenedMinScore);
  }

  /** 関心の管理で用いる利用先の検出候補一覧を返す。 */
  private List<InterestTopicCandidate> discoverCandidates(
      List<String> pastQueries, int maxTopics, double minScore) {
    List<ConversationSnippet> snippets =
        conversationHistoryService.findRecentUserMessages(
            properties.getLookbackDays(), properties.getMessageLimit());
    return interestTopicExtractor.extract(snippets, maxTopics, pastQueries).stream()
        .filter(candidate -> candidate.score() >= minScore)
        .limit(maxTopics)
        .toList();
  }
}
