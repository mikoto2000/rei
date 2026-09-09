package dev.mikoto2000.rei.event;

import dev.mikoto2000.rei.core.execution.ExecutionType;

public record BackgroundExecutionPayload(String executionId,ExecutionType executionType,String status,
    String request,String output) implements AgentEventPayload {}
