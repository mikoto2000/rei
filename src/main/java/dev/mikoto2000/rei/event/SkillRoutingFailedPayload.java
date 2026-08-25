package dev.mikoto2000.rei.event;

/** Skill routing の失敗。機密情報や stack trace は含めない。 */
public record SkillRoutingFailedPayload(
    long durationMs,
    int candidateCount,
    int routingInvocation,
    ErrorInformation error) implements AgentEventPayload {
}
