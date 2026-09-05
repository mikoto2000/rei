package dev.mikoto2000.rei.event;

public record LlmResponseFirstTokenPayload(String requestId, long durationMs)
    implements AgentEventPayload {
}
