package dev.mikoto2000.rei.event;

/** Skill routing の開始時点で取得できる計測値。 */
public record SkillRoutingStartedPayload(int candidateCount, int routingInvocation)
    implements AgentEventPayload {
}
