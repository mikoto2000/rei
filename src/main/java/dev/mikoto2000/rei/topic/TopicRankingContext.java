package dev.mikoto2000.rei.topic;

import java.util.List;

public record TopicRankingContext(List<String> recentTopics) {
  public TopicRankingContext {
    recentTopics = recentTopics == null ? List.of() : List.copyOf(recentTopics);
  }
}
