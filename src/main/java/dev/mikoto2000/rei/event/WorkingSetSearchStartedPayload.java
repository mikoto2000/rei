package dev.mikoto2000.rei.event;

/** Working Set search lifecycle start; query is bounded by the publisher. */
public record WorkingSetSearchStartedPayload(
    String searchId, String query, String strategy, int workingSetSizeBefore) implements AgentEventPayload {
}
