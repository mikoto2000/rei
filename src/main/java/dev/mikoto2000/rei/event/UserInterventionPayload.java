package dev.mikoto2000.rei.event;

public record UserInterventionPayload(String interventionId, String text) implements AgentEventPayload {}
