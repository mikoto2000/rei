package dev.mikoto2000.rei.event;

/**
 * LLM への依頼送信開始。プロンプト本文は機密情報保護のため保持しない。
 *
 * @param requestId 要求の識別子
 * @param feature 機能
 */
public record LlmRequestStartedPayload(String requestId, String feature)
    implements AgentEventPayload {}
