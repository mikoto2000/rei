package dev.mikoto2000.rei.event;

import java.time.Instant;

import dev.mikoto2000.rei.topic.TopicGenerationStage;

public record TopicGenerationFailedPayload(
    String topicGenerationId,
    TopicGenerationStage stage,
    String errorSummary,
    Instant failedAt) implements AgentEventPayload {
}
