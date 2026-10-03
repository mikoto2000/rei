package dev.mikoto2000.rei.event;
public record CheckpointLifecyclePayload(String taskId,long revision,String previousRunId,Long checkpointRevision,String decision) implements AgentEventPayload {}
