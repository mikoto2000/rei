package dev.mikoto2000.rei.event;

/**
 * Skill routing の失敗。機密情報や stack trace は含めない。
 *
 * @param durationMs 継続時間ミリ秒
 * @param candidateCount 候補件数
 * @param routingInvocation 振り分けInvocation
 * @param error エラー
 */
public record SkillRoutingFailedPayload(
    long durationMs, int candidateCount, int routingInvocation, ErrorInformation error)
    implements AgentEventPayload {}
