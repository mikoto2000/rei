package dev.mikoto2000.rei.event;

import java.time.Instant;

public record TopicAutoSpeakSuppressedPayload(
    String reason,
    Instant suppressedAt) implements AgentEventPayload {
}
