package dev.mikoto2000.rei.event;

public record BackgroundProcessStartedPayload(String processId, long pid, String command, String workingDirectory)
    implements AgentEventPayload {
}
