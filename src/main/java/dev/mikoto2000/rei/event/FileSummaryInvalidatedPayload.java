package dev.mikoto2000.rei.event;

public record FileSummaryInvalidatedPayload(String path) implements AgentEventPayload {
}
