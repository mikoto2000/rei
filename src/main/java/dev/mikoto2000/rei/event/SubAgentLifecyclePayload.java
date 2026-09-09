package dev.mikoto2000.rei.event;

/** No prompts, intermediate messages or raw exceptions are persisted here. */
public record SubAgentLifecyclePayload(String parentRunId, String subAgentRunId, String agentId,
    String taskSummary, String status, long duration, String failureReason) implements AgentEventPayload { }
