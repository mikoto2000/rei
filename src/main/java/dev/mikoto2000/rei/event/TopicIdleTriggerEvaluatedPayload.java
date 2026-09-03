package dev.mikoto2000.rei.event;

import java.time.Instant;

import dev.mikoto2000.rei.topic.IdleTriggerRejectReason;

public record TopicIdleTriggerEvaluatedPayload(
    boolean accepted,
    long idleDurationMs,
    long requiredIdleMs,
    IdleTriggerRejectReason rejectReason,
    Instant evaluatedAt) implements AgentEventPayload {
}
