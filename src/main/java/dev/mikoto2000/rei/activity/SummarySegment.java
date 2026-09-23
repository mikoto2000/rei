package dev.mikoto2000.rei.activity;

import java.time.*;
import java.util.List;

/** Display projection only. Evidence and fine session references remain available verbatim. */
public record SummarySegment(Instant startedAt,Instant endedAt,long observedSeconds,ActivityRoles roles,
    List<ActivityRecord> evidence,List<String> fineSessionIds) {
  public SummarySegment { evidence=List.copyOf(evidence);fineSessionIds=List.copyOf(fineSessionIds); }
  public long unobservedSeconds() { return Math.max(0,Duration.between(startedAt,endedAt).getSeconds()-observedSeconds); }
}
