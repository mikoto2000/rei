package dev.mikoto2000.rei.event;

/** 思考過程の生成完了。 */
public record ThinkingCompletedPayload(String thinkingId, String text) implements AgentEventPayload {
}
