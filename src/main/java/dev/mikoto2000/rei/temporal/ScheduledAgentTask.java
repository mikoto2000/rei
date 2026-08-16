package dev.mikoto2000.rei.temporal;

import java.time.Instant;

public record ScheduledAgentTask(
    String id,
    Instant createdAt,
    Instant executeAt,
    String action,
    String conversationId) {
}
