package dev.mikoto2000.rei.event;

import dev.mikoto2000.rei.topic.TopicSpeakSkipReason;
import java.time.Instant;

public record TopicSpeakSkippedPayload(
    String topicGenerationId,
    String candidateId,
    TopicSpeakSkipReason reason,
    Instant nextSpeakAllowedAt)
    implements AgentEventPayload {}
