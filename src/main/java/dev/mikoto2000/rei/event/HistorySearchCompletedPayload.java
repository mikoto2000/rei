package dev.mikoto2000.rei.event;

/** Counts only: no query, conversation content, or repository-local paths. */
public record HistorySearchCompletedPayload(String preferredProjectId, String scope, int searchedProjectCount,
    int currentProjectHitCount, int crossProjectHitCount) implements AgentEventPayload {}
