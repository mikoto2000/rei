package dev.mikoto2000.rei.interest;

import java.util.List;

public interface InterestTopicExtractor {

  List<InterestTopicCandidate> extract(List<ConversationSnippet> snippets, int maxTopics);
}
