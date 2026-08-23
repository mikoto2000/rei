package dev.mikoto2000.rei.event;

/** Aggregate-only result of a Working Set search lifecycle. */
public record WorkingSetSearchCompletedPayload(
    String searchId,
    long durationMs,
    int hitCount,
    int candidateCount,
    int selectedCount,
    int alreadyPresentCount,
    int workingSetSizeBefore,
    int workingSetSizeAfter) implements AgentEventPayload {
}
