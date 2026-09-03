package dev.mikoto2000.rei.topic;

public record TopicCandidateState(
    String candidateId,
    TopicCandidateStatus status,
    TopicType type,
    TopicSource source,
    String topic,
    String reason,
    TopicScoreBreakdown score,
    TopicRejectionReason rejectionReason) {
}
