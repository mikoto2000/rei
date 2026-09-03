package dev.mikoto2000.rei.topic;

import java.util.List;

public interface TopicRanker {
  List<RankedTopicCandidate> rank(List<TopicCandidate> candidates, TopicRankingContext context);
}
