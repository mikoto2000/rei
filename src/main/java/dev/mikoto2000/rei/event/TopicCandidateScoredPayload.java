package dev.mikoto2000.rei.event;

import dev.mikoto2000.rei.topic.TopicScoreBreakdown;

public record TopicCandidateScoredPayload(
    String topicGenerationId,
    String candidateId,
    TopicScoreBreakdown score) implements AgentEventPayload {
}
