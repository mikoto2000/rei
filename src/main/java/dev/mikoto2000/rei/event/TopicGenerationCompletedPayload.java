package dev.mikoto2000.rei.event;

import java.time.Instant;

public record TopicGenerationCompletedPayload(
    String topicGenerationId,
    int candidateCount,
    int scoredCount,
    int rejectedCount,
    String selectedCandidateId,
    boolean spoken,
    long durationMs,
    Instant completedAt) implements AgentEventPayload {
}
