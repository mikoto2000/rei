package dev.mikoto2000.rei.activity.behavior;

import java.time.Instant;
import java.util.List;

/** Delivery outcome, separate from assessments and pre-generation cooldown reservations. No message body. */
public record BehaviorTimelineEvent(String id,Instant timestamp,BehaviorSeverity severity,BehaviorAssessment.Reason trigger,
    Outcome outcome,String reason,long continuousEntertainmentSeconds,List<BehaviorAssessment.Window> windows) {
  public enum Outcome { EMITTED, SUPPRESSED, FAILED }
  public BehaviorTimelineEvent {windows=List.copyOf(windows);}
}
