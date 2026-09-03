package dev.mikoto2000.rei.topic;

import java.time.Instant;

public record AgentMessage(
    String id,
    String role,
    String content,
    MessageOrigin origin,
    Instant createdAt) {
}
