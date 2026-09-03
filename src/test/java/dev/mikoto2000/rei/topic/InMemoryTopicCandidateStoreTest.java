package dev.mikoto2000.rei.topic;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

class InMemoryTopicCandidateStoreTest {

  @Test
  void returnsCurrentCandidatesWithinMaxAge() {
    InMemoryTopicCandidateStore store = new InMemoryTopicCandidateStore();
    TopicCandidate candidate = candidate();
    store.replace(List.of(candidate), Instant.parse("2026-09-02T00:00:00Z"));

    assertEquals(List.of(candidate), store.currentCandidates(
        Instant.parse("2026-09-02T00:10:00Z"), Duration.ofMinutes(30)));
  }

  @Test
  void returnsEmptyWhenCandidatesAreStale() {
    InMemoryTopicCandidateStore store = new InMemoryTopicCandidateStore();
    store.replace(List.of(candidate()), Instant.parse("2026-09-02T00:00:00Z"));

    assertEquals(List.of(), store.currentCandidates(
        Instant.parse("2026-09-02T00:31:00Z"), Duration.ofMinutes(30)));
  }

  private TopicCandidate candidate() {
    return new TopicCandidate("id", "topic", "reason", TopicType.FOLLOW_UP, TopicSource.CONVERSATION,
        1, 1, 1, 0, 1, Instant.parse("2026-09-02T00:00:00Z"));
  }
}
