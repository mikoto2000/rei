package dev.mikoto2000.rei.interest;

public record InterestTopicCandidate(
    String topic,
    String reason,
    String searchQuery,
    double score) {
}
