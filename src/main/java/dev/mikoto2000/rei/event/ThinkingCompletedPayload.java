package dev.mikoto2000.rei.event;

/**
 * 思考過程の生成完了。
 *
 * @param thinkingId 推論識別子
 * @param text テキスト
 */
public record ThinkingCompletedPayload(String thinkingId, String text)
    implements AgentEventPayload {}
