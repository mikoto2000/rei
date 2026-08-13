package dev.mikoto2000.rei.agent.progress;

public record ProgressTrackerSnapshot(
    AgentProgressState state,
    ProgressEvaluation evaluation,
    int noProgressCount,
    int maxNoProgressIterations,
    boolean shouldStop) {
}
