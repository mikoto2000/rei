package dev.mikoto2000.rei.event;
public record GoalLifecyclePayload(String goalId,String status,int attempts,int maxRuns,int llmCallsUsed,int maxLlmCalls,String reason) implements AgentEventPayload {}
