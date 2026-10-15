package dev.mikoto2000.rei.event;

import dev.mikoto2000.rei.topic.TopicGenerationStage;
import java.time.Instant;

public record TopicGenerationFailedPayload(
    String topicGenerationId, TopicGenerationStage stage, String errorSummary, Instant failedAt)
    implements AgentEventPayload {}
