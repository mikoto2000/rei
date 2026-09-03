package dev.mikoto2000.rei.topic;

public record RankedTopicCandidate(TopicCandidate candidate, TopicScoreBreakdown score,
    TopicRejectionReason rejectionReason) {
  public double finalScore() {
    return score.finalScore();
  }
}
