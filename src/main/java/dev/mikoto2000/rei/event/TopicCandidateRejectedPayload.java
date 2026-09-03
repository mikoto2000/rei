package dev.mikoto2000.rei.event;

import dev.mikoto2000.rei.topic.TopicRejectionReason;

public record TopicCandidateRejectedPayload(
    String topicGenerationId,
    String candidateId,
    TopicRejectionReason reason,
    Double score) implements AgentEventPayload {
}
