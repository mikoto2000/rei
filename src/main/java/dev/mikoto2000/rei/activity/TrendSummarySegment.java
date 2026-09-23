package dev.mikoto2000.rei.activity;

import java.time.*;
import java.util.List;

/** A bounded period's observed trend, not a continuous activity or an analytics input. */
public record TrendSummarySegment(Instant startedAt,Instant endedAt,Continuity continuity,String theme,
    long observedSeconds,long knownSeconds,long unknownSeconds,List<String> categories,List<String> projects,
    List<String> labels,List<SummarySegment> sourceSegments,List<ActivityRecord> evidence,List<String> fineSessionIds) {
  public enum Continuity { CONTINUOUS, INTERMITTENT, MIXED }
  public TrendSummarySegment {
    categories=List.copyOf(categories);projects=List.copyOf(projects);labels=List.copyOf(labels);
    sourceSegments=List.copyOf(sourceSegments);evidence=List.copyOf(evidence);fineSessionIds=List.copyOf(fineSessionIds);
  }
  public long unobservedSeconds() {return Math.max(0,Duration.between(startedAt,endedAt).getSeconds()-observedSeconds);}
}
