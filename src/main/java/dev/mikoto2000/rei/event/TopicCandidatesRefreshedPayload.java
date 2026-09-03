package dev.mikoto2000.rei.event;

import java.time.Instant;

public record TopicCandidatesRefreshedPayload(
    int candidateCount,
    Instant refreshedAt) implements AgentEventPayload {
}
