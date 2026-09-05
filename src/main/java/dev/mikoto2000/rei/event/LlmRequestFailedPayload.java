package dev.mikoto2000.rei.event;

public record LlmRequestFailedPayload(String requestId, long durationMs, ErrorInformation error)
    implements AgentEventPayload {
}
