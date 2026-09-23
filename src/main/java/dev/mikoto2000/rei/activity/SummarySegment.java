package dev.mikoto2000.rei.activity;

import java.time.*;
import java.util.List;

/** Display projection only. Evidence and fine session references remain available verbatim. */
public record SummarySegment(Instant startedAt,Instant endedAt,long observedSeconds,ActivityRoles roles,
    List<ActivityRecord> evidence,List<String> fineSessionIds,String theme,List<String> primaryCategories) {
  public SummarySegment(Instant startedAt,Instant endedAt,long observedSeconds,ActivityRoles roles,List<ActivityRecord> evidence,List<String> fineSessionIds) {
    this(startedAt,endedAt,observedSeconds,roles,evidence,fineSessionIds,SummaryGroupingPolicy.theme(roles));
  }
  public SummarySegment(Instant startedAt,Instant endedAt,long observedSeconds,ActivityRoles roles,List<ActivityRecord> evidence,List<String> fineSessionIds,String theme) {
    this(startedAt,endedAt,observedSeconds,roles,evidence,fineSessionIds,theme,List.of(roles.category()));
  }
  public SummarySegment { evidence=List.copyOf(evidence);fineSessionIds=List.copyOf(fineSessionIds);primaryCategories=List.copyOf(primaryCategories); }
  public long unobservedSeconds() { return Math.max(0,Duration.between(startedAt,endedAt).getSeconds()-observedSeconds); }
}
