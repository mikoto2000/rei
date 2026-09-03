package dev.mikoto2000.rei.event;

import java.time.Instant;

import dev.mikoto2000.rei.topic.TopicSpeakSkipReason;

public record TopicSpeakSkippedPayload(
    String topicGenerationId,
    String candidateId,
    TopicSpeakSkipReason reason,
    Instant nextSpeakAllowedAt) implements AgentEventPayload {
}
