package dev.mikoto2000.rei.topic;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

public class InMemoryTopicCandidateStore implements TopicCandidateStore {
  private List<TopicCandidate> candidates = List.of();
  private Instant generatedAt;

  @Override
  public synchronized void replace(List<TopicCandidate> candidates, Instant generatedAt) {
    this.candidates = candidates == null ? List.of() : List.copyOf(candidates);
    this.generatedAt = generatedAt;
  }

  @Override
  public synchronized List<TopicCandidate> currentCandidates(Instant now, Duration maxAge) {
    if (generatedAt == null || candidates.isEmpty()) return List.of();
    if (maxAge != null && Duration.between(generatedAt, now).compareTo(maxAge) > 0) return List.of();
    return candidates;
  }
}
