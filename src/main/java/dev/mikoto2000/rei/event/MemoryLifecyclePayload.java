package dev.mikoto2000.rei.event;

/** Counts and lifecycle only; memory content and source text are never included. */
public record MemoryLifecyclePayload(String sleepRunId, boolean preview, int processedTurns,
    int candidateCount, int memoryCount, String status) implements AgentEventPayload { }
