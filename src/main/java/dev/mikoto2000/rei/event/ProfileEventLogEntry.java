package dev.mikoto2000.rei.event;

import java.time.Instant;
import java.util.Map;

public record ProfileEventLogEntry(
    String id,
    long sequence,
    Instant timestamp,
    String type,
    int version,
    String sessionId,
    String turnId,
    String runId,
    String correlationId,
    String parentEventId,
    String payloadType,
    Map<String, Object> payload) {
}
