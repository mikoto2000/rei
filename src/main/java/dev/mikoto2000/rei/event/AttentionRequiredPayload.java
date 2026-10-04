package dev.mikoto2000.rei.event;
public record AttentionRequiredPayload(String attentionId,String kind,String message) implements AgentEventPayload {}
