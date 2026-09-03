package dev.mikoto2000.rei.event;

import java.time.Instant;

public record TopicGenerationStartedPayload(
    String topicGenerationId,
    String trigger,
    Instant startedAt) implements AgentEventPayload {
}
