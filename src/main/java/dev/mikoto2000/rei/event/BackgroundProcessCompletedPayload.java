package dev.mikoto2000.rei.event;

public record BackgroundProcessCompletedPayload(String processId, long pid, Integer exitCode, double elapsedSeconds)
    implements AgentEventPayload {
}
