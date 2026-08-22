package dev.mikoto2000.rei.ui.projection;

import java.time.Instant;

public record MessageView(
    String messageId,
    String role,
    MessageStatus status,
    String text,
    Instant startedAt,
    Instant completedAt) {
}
