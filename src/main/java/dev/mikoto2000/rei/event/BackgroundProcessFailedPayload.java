package dev.mikoto2000.rei.event;

public record BackgroundProcessFailedPayload(String processId, String command, ErrorInformation error)
    implements AgentEventPayload {
}
