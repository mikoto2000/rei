package dev.mikoto2000.rei.activity.behavior;

import java.time.Instant;

/** One durable checkpoint; no per-minute history or screenshots. */
public record BehaviorState(String episodeId,Instant recoveredThrough,Instant lastNotificationAt,
    BehaviorSeverity lastNotifiedSeverity,BehaviorAssessment.Reason lastReason,BehaviorSeverity currentSeverity) {
  public static BehaviorState empty() {return new BehaviorState(null,null,null,BehaviorSeverity.NONE,BehaviorAssessment.Reason.NONE,BehaviorSeverity.NONE);}
  public BehaviorState notified(BehaviorAssessment a) {return new BehaviorState(episodeId,recoveredThrough,a.evaluatedAt(),a.severity(),a.reason(),a.severity());}
}
