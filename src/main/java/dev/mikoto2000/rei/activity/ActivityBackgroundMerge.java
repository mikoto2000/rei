package dev.mikoto2000.rei.activity;

import java.util.*;

/** Background results supplement the same observation, never replace foreground evidence or its primary role. */
final class ActivityBackgroundMerge {
  private ActivityBackgroundMerge() {}
  static ActivityRecord merge(ActivityRecord foreground,ActivityExtractor.Result background,double threshold) {
    if(background.confidence()<threshold) return foreground;
    var roles=new ActivityRolePolicy(threshold);
    var primary=roles.classify(foreground).primary();
    var candidates=new ArrayList<>(foreground.inference().activities());
    for(var activity:background.inference().activities()) {
      if(candidates.contains(activity)) continue;
      candidates.add(activity);
      if(!Objects.equals(primary,roles.classify(withCandidates(foreground,candidates)).primary())) candidates.removeLast();
    }
    return withCandidates(foreground,candidates);
  }
  private static ActivityRecord withCandidates(ActivityRecord r,List<ActivityRecord.Activity> candidates) {
    return new ActivityRecord(r.id(),r.capturedAt(),r.durationEstimate(),r.observations(),r.foreground(),
        new ActivityRecord.Inference(r.inference().summary(),candidates),r.confidence(),r.screenshotReferences(),r.changeAmount(),r.duplicate(),r.continuityId());
  }
}
