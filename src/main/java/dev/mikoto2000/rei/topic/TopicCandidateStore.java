package dev.mikoto2000.rei.topic;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

public interface TopicCandidateStore {
  void replace(List<TopicCandidate> candidates, Instant generatedAt);
  List<TopicCandidate> currentCandidates(Instant now, Duration maxAge);
}
