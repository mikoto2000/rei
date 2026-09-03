package dev.mikoto2000.rei.topic;

import java.text.Normalizer;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class DeterministicTopicRanker implements TopicRanker {
  public static final double PRIORITY_WEIGHT = 0.20d;
  public static final double FRESHNESS_WEIGHT = 0.20d;
  public static final double USEFULNESS_WEIGHT = 0.30d;
  public static final double CONFIDENCE_WEIGHT = 0.20d;
  public static final double INTRUSIVENESS_WEIGHT = 0.25d;
  public static final double REPETITION_PENALTY = 0.40d;

  @Override
  public List<RankedTopicCandidate> rank(List<TopicCandidate> candidates, TopicRankingContext context) {
    if (candidates == null || candidates.isEmpty()) return List.of();
    TopicRankingContext safeContext = context == null ? new TopicRankingContext(List.of()) : context;
    return candidates.stream()
        .map(candidate -> rank(candidate, safeContext))
        .sorted(Comparator.comparingDouble(RankedTopicCandidate::finalScore).reversed())
        .toList();
  }

  private RankedTopicCandidate rank(TopicCandidate candidate, TopicRankingContext context) {
    boolean repeated = context.recentTopics().stream()
        .map(DeterministicTopicRanker::normalize)
        .anyMatch(value -> value.equals(normalize(candidate.topic())));
    double priority = candidate.priority() * PRIORITY_WEIGHT;
    double freshness = candidate.freshness() * FRESHNESS_WEIGHT;
    double usefulness = candidate.usefulness() * USEFULNESS_WEIGHT;
    double confidence = candidate.confidence() * CONFIDENCE_WEIGHT;
    double intrusiveness = candidate.intrusiveness() * INTRUSIVENESS_WEIGHT;
    double repetition = repeated ? REPETITION_PENALTY : 0.0d;
    double finalScore = clamp(priority + freshness + usefulness + confidence - intrusiveness - repetition);
    TopicScoreBreakdown score = new TopicScoreBreakdown(priority, freshness, usefulness, confidence, intrusiveness,
        repetition, finalScore);
    return new RankedTopicCandidate(candidate, score, repeated ? TopicRejectionReason.RECENTLY_SPOKEN : null);
  }

  static String normalize(String value) {
    if (value == null) return "";
    return Normalizer.normalize(value, Normalizer.Form.NFKC)
        .toLowerCase(Locale.ROOT)
        .replaceAll("\\s+", "")
        .trim();
  }

  private double clamp(double value) {
    return Math.max(0.0d, Math.min(1.0d, value));
  }
}
