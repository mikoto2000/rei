package dev.mikoto2000.rei.event;

public record CheckpointSavedPayload(String taskId, String reason, int workingFileCount) implements AgentEventPayload {
}
