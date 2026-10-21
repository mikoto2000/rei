package dev.mikoto2000.rei.event;

/**
 * 思考過程の追加テキスト。
 *
 * @param thinkingId 推論識別子
 * @param delta 差分
 */
public record ThinkingDeltaPayload(String thinkingId, String delta) implements AgentEventPayload {}
