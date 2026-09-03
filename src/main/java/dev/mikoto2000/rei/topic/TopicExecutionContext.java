package dev.mikoto2000.rei.topic;

import java.time.Instant;

public record TopicExecutionContext(
    String runId,
    Instant now,
    Instant lastUserActivityAt,
    Instant lastAgentActivityAt,
    boolean agentBusy,
    TopicTrigger trigger) {
}
