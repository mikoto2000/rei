package dev.mikoto2000.rei.topic;

import java.text.Normalizer;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** 話題の管理に必要な再現可能な話題Rankerを扱う。 */
public class DeterministicTopicRanker implements TopicRanker {
  /** 優先度重み。 */
  public static final double PRIORITY_WEIGHT = 0.20d;

  /** 情報の鮮度重み。 */
  public static final double FRESHNESS_WEIGHT = 0.20d;

  /** 有用性重み。 */
  public static final double USEFULNESS_WEIGHT = 0.30d;

  /** 確信度重み。 */
  public static final double CONFIDENCE_WEIGHT = 0.20d;

  /** 割り込みの負担重み。 */
  public static final double INTRUSIVENESS_WEIGHT = 0.25d;

  /** 同じ作業の繰り返し減点。 */
  public static final double REPETITION_PENALTY = 0.40d;

  /** 話題の管理で用いる順位を返す。 */
  @Override
  public List<RankedTopicCandidate> rank(
      List<TopicCandidate> candidates, TopicRankingContext context) {
    if (candidates == null || candidates.isEmpty()) return List.of();
    TopicRankingContext safeContext =
        context == null ? new TopicRankingContext(List.of()) : context;
    return candidates.stream()
        .map(candidate -> rank(candidate, safeContext))
        .sorted(Comparator.comparingDouble(RankedTopicCandidate::finalScore).reversed())
        .toList();
  }

  /** 話題の管理で用いる順位を返す。 */
  private RankedTopicCandidate rank(TopicCandidate candidate, TopicRankingContext context) {
    boolean repeated =
        context.recentTopics().stream()
            .map(DeterministicTopicRanker::normalize)
            .anyMatch(value -> value.equals(normalize(candidate.topic())));
    double priority = candidate.priority() * PRIORITY_WEIGHT;
    double freshness = candidate.freshness() * FRESHNESS_WEIGHT;
    double usefulness = candidate.usefulness() * USEFULNESS_WEIGHT;
    double confidence = candidate.confidence() * CONFIDENCE_WEIGHT;
    double intrusiveness = candidate.intrusiveness() * INTRUSIVENESS_WEIGHT;
    double repetition = repeated ? REPETITION_PENALTY : 0.0d;
    double finalScore =
        clamp(priority + freshness + usefulness + confidence - intrusiveness - repetition);
    TopicScoreBreakdown score =
        new TopicScoreBreakdown(
            priority, freshness, usefulness, confidence, intrusiveness, repetition, finalScore);
    return new RankedTopicCandidate(
        candidate, score, repeated ? TopicRejectionReason.RECENTLY_SPOKEN : null);
  }

  /** 同じ意味の指定を共通に扱えるよう、表記や形式をそろえる。 */
  static String normalize(String value) {
    if (value == null) return "";
    return Normalizer.normalize(value, Normalizer.Form.NFKC)
        .toLowerCase(Locale.ROOT)
        .replaceAll("\\s+", "")
        .trim();
  }

  /** 話題の管理で用いる許容範囲を返す。 */
  private double clamp(double value) {
    return Math.max(0.0d, Math.min(1.0d, value));
  }
}
