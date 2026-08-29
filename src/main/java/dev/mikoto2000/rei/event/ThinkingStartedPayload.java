package dev.mikoto2000.rei.event;

/** 思考過程の生成開始。 */
public record ThinkingStartedPayload(String thinkingId) implements AgentEventPayload {
}
