package dev.mikoto2000.rei.topic;

import java.time.Instant;
import java.util.List;

public record TopicGeneratorState(
    TopicGenerationStatus status,
    String topicGenerationId,
    List<TopicCandidateState> candidates,
    String selectedCandidateId,
    TopicSpeakSkipReason skipReason,
    Instant startedAt,
    Instant completedAt,
    Instant lastSpokenAt,
    boolean spoken,
    long lastSequence) {

  public static TopicGeneratorState idle() {
    return new TopicGeneratorState(TopicGenerationStatus.IDLE, null, List.of(), null, null, null, null, null, false, 0L);
  }
}
