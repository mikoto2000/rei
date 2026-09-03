package dev.mikoto2000.rei.event;

public record TopicCandidateGeneratedPayload(
    String topicGenerationId,
    String candidateId,
    String topicType,
    String source,
    String topicSummary,
    String reasonSummary,
    Double priority,
    Double freshness,
    Double usefulness,
    Double intrusiveness,
    Double confidence) implements AgentEventPayload {
}
