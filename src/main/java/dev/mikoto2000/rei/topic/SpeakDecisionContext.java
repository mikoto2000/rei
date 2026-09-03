package dev.mikoto2000.rei.topic;

import java.time.Instant;
import java.util.List;

public record SpeakDecisionContext(
    Instant currentTime,
    Instant lastTopicSpokenAt,
    List<String> recentTopics,
    boolean userRecentlyActive,
    boolean agentBusy) {

  public SpeakDecisionContext {
    if (currentTime == null) throw new IllegalArgumentException("currentTime must not be null");
    recentTopics = recentTopics == null ? List.of() : List.copyOf(recentTopics);
  }
}
