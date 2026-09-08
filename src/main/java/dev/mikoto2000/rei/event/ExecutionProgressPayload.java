package dev.mikoto2000.rei.event;

import dev.mikoto2000.rei.core.stagnation.ProgressEvidence;

public record ExecutionProgressPayload(ProgressEvidence evidence, int consecutiveNoProgressIterations,
    int threshold, int stagnationReplanCount, int maxStagnationReplans, String reason) implements AgentEventPayload {}
