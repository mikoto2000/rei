package dev.mikoto2000.rei.topic;

import java.time.Instant;
import java.util.List;

public record TopicGenerationContext(
    List<ConversationTopicMessage> recentConversation,
    List<WorkingSetTopicItem> workingSet,
    Instant currentTime,
    List<String> recentTopics) {

  public TopicGenerationContext {
    recentConversation = recentConversation == null ? List.of() : List.copyOf(recentConversation);
    workingSet = workingSet == null ? List.of() : List.copyOf(workingSet);
    if (currentTime == null) throw new IllegalArgumentException("currentTime must not be null");
    recentTopics = recentTopics == null ? List.of() : List.copyOf(recentTopics);
  }
}
