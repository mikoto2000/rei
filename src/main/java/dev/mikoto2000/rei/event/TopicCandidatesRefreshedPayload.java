package dev.mikoto2000.rei.event;

import java.time.Instant;
import java.util.List;

public record TopicCandidatesRefreshedPayload(
    int candidateCount,
    List<String> topics,
    Instant refreshedAt) implements AgentEventPayload {
  public TopicCandidatesRefreshedPayload {
    topics = List.copyOf(topics);
  }
}
