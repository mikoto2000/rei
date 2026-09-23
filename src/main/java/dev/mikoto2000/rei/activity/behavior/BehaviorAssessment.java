package dev.mikoto2000.rei.activity.behavior;

import java.time.Instant;
import java.util.List;

/** Durations are observed estimates in seconds, never wall-clock engagement measurements. */
public record BehaviorAssessment(BehaviorSeverity severity,Reason reason,Instant evaluatedAt,
    long continuousEntertainmentSeconds,List<Window> windows,List<String> dominantCategories,List<String> services,
    double confidence,Instant latestObservedAt,boolean activeEntertainment,Instant recoveredAt,
    Instant episodeStartedAt,boolean episodeQualified) {
  public enum Reason { NONE, CONTINUOUS_ENTERTAINMENT, ENTERTAINMENT_RATIO_HIGH, BOTH }
  public record Window(int durationMinutes,long entertainmentObservedSeconds,long eligibleObservedSeconds,double entertainmentRatio,BehaviorSeverity severity,long observedSeconds) {}
  public BehaviorAssessment {windows=List.copyOf(windows);dominantCategories=List.copyOf(dominantCategories);services=List.copyOf(services);}
}
