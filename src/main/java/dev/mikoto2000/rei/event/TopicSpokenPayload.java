package dev.mikoto2000.rei.event;

import java.time.Instant;

public record TopicSpokenPayload(
    String topicGenerationId,
    String candidateId,
    String messageId,
    Instant spokenAt,
    String messageSummary) implements AgentEventPayload {
}
