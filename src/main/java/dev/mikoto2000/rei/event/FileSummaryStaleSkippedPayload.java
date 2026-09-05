package dev.mikoto2000.rei.event;

public record FileSummaryStaleSkippedPayload(String path) implements AgentEventPayload {
}
