package dev.mikoto2000.rei.event;

/**
 * LLM からの回答受信完了。回答本文は Message イベントで扱う。
 *
 * @param requestId 要求の識別子
 * @param durationMs 継続時間ミリ秒
 */
public record LlmResponseCompletedPayload(String requestId, long durationMs)
    implements AgentEventPayload {}
