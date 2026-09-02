package dev.mikoto2000.rei.event;

/** LLM への依頼送信開始。プロンプト本文は機密情報保護のため保持しない。 */
public record LlmRequestStartedPayload(String requestId, String feature) implements AgentEventPayload {
}
