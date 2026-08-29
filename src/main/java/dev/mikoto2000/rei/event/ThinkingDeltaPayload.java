package dev.mikoto2000.rei.event;

/** 思考過程の追加テキスト。 */
public record ThinkingDeltaPayload(String thinkingId, String delta) implements AgentEventPayload {
}
