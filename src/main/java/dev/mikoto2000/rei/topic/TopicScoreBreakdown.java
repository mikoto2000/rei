package dev.mikoto2000.rei.topic;

public record TopicScoreBreakdown(
    double priorityContribution,
    double freshnessContribution,
    double usefulnessContribution,
    double confidenceContribution,
    double intrusivenessPenalty,
    double repetitionPenalty,
    double finalScore) {
}
