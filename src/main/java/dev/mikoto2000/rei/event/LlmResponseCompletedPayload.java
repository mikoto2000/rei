package dev.mikoto2000.rei.event;

/** LLM からの回答受信完了。回答本文は Message イベントで扱う。 */
public record LlmResponseCompletedPayload(String requestId, long durationMs) implements AgentEventPayload {
}
