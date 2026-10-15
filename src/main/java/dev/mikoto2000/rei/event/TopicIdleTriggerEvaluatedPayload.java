package dev.mikoto2000.rei.event;

import dev.mikoto2000.rei.topic.IdleTriggerRejectReason;
import java.time.Instant;

public record TopicIdleTriggerEvaluatedPayload(
    boolean accepted,
    long idleDurationMs,
    long requiredIdleMs,
    IdleTriggerRejectReason rejectReason,
    Instant evaluatedAt)
    implements AgentEventPayload {}
