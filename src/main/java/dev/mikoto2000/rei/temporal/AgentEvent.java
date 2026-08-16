package dev.mikoto2000.rei.temporal;

import java.time.Instant;
import java.util.Map;

public record AgentEvent(
    Instant timestamp,
    String type,
    String subject,
    Map<String, Object> payload) {
}
