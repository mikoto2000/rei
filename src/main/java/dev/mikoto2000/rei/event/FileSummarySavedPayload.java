package dev.mikoto2000.rei.event;

public record FileSummarySavedPayload(String path, int summaryCharacters) implements AgentEventPayload {
}
