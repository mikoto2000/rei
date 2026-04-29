package dev.mikoto2000.rei.interest;

import java.util.List;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ConversationInterestService {

  private final ConversationHistoryService conversationHistoryService;
  private final InterestTopicExtractor interestTopicExtractor;
  private final InterestProperties properties;

  public List<InterestTopicCandidate> discoverCandidates() {
    return discoverCandidates(List.of());
  }

  public List<InterestTopicCandidate> discoverCandidates(List<String> pastQueries) {
    List<ConversationSnippet> snippets = conversationHistoryService.findRecentUserMessages(
        properties.getLookbackDays(),
        properties.getMessageLimit());
    return interestTopicExtractor.extract(snippets, properties.getMaxTopics(), pastQueries).stream()
        .filter(candidate -> candidate.score() >= properties.getMinScore())
        .limit(properties.getMaxTopics())
        .toList();
  }
}
