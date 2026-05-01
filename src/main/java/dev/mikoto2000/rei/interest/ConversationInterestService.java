package dev.mikoto2000.rei.interest;

import java.util.List;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ConversationInterestService {

  private static final int FALLBACK_MAX_TOPICS_MULTIPLIER = 2;
  private static final double FALLBACK_MIN_SCORE_DELTA = 0.2d;

  private final ConversationHistoryService conversationHistoryService;
  private final InterestTopicExtractor interestTopicExtractor;
  private final InterestProperties properties;

  public List<InterestTopicCandidate> discoverCandidates() {
    return discoverCandidates(List.of());
  }

  public List<InterestTopicCandidate> discoverCandidates(List<String> pastQueries) {
    return discoverCandidates(pastQueries, properties.getMaxTopics(), properties.getMinScore());
  }

  public List<InterestTopicCandidate> discoverFallbackCandidates(List<String> pastQueries) {
    int broadenedMaxTopics = Math.max(properties.getMaxTopics() * FALLBACK_MAX_TOPICS_MULTIPLIER, properties.getMaxTopics());
    double broadenedMinScore = Math.max(0.0d, properties.getMinScore() - FALLBACK_MIN_SCORE_DELTA);
    return discoverCandidates(pastQueries, broadenedMaxTopics, broadenedMinScore);
  }

  private List<InterestTopicCandidate> discoverCandidates(List<String> pastQueries, int maxTopics, double minScore) {
    List<ConversationSnippet> snippets = conversationHistoryService.findRecentUserMessages(
        properties.getLookbackDays(),
        properties.getMessageLimit());
    return interestTopicExtractor.extract(snippets, maxTopics, pastQueries).stream()
        .filter(candidate -> candidate.score() >= minScore)
        .limit(maxTopics)
        .toList();
  }
}
