package dev.mikoto2000.rei.event;

public record TopicSelectedPayload(
    String topicGenerationId,
    String candidateId,
    Double score,
    Integer rank) implements AgentEventPayload {
}
