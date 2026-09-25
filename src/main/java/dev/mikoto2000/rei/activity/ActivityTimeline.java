package dev.mikoto2000.rei.activity;

import java.time.*;
import java.util.*;

public record ActivityTimeline(ActivityStore store,Clock clock,SemanticSessionPolicy summaryPolicy,DailySummaryService dailySummaries) {
  public ActivityTimeline(ActivityStore store,Clock clock,SemanticSessionPolicy summaryPolicy) {
    this(store,clock,summaryPolicy,DailySummaryService.local());
  }
  public ActivityTimeline(ActivityStore store,Clock clock) {
    this(store,clock,new SemanticSessionPolicy(Duration.ofSeconds(120),Duration.ofSeconds(300),Duration.ofSeconds(120),.5,clock.getZone()));
  }
  public List<ActivitySession> findByDate(LocalDate date) {
    return store.findBetween(date.atStartOfDay(clock.getZone()).toInstant(),date.plusDays(1).atStartOfDay(clock.getZone()).toInstant());
  }
  public List<ActivitySession> findBetween(Instant start,Instant end) {
    validateRange(start,end);
    return store.findBetween(start,end);
  }
  private static void validateRange(Instant start,Instant end) {
    if(!start.isBefore(end) || Duration.between(start,end).compareTo(Duration.ofDays(31))>0)
      throw new IllegalArgumentException("Specify an increasing range of at most 31 days");
  }
  public List<ActivitySession> query(String day) {
    return findByDate(date(day));
  }
  LocalDate date(String day) {
    return switch(day) {case "today","summary" -> LocalDate.now(clock);case "yesterday" -> LocalDate.now(clock).minusDays(1);default -> LocalDate.parse(day);};
  }
  /** Listing projection needs evidence, not persisted fine-session references: one bulk record query. */
  public List<SummarySegment> timelineSegments(LocalDate date) {
    var start=date.atStartOfDay(clock.getZone()).toInstant();var end=date.plusDays(1).atStartOfDay(clock.getZone()).toInstant();
    return new SummaryGroupingPolicy(summaryPolicy).aggregate(summaryPolicy.aggregate(store.findRecordsBetween(start,end),start,end));
  }
  public List<SummarySegment> summarySegments(String day) {
    var date=date(day);
    return summaryBetween(date.atStartOfDay(clock.getZone()).toInstant(),date.plusDays(1).atStartOfDay(clock.getZone()).toInstant());
  }
  public List<SummarySegment> summaryBetween(Instant start,Instant end) {
    validateRange(start,end);
    var fine=store.findBetween(start,end);
    return summaryBetween(start,end,fine);
  }
  private List<SummarySegment> summaryBetween(Instant start,Instant end,List<ActivitySession> fine) {
    var records=store.findRecordsBetween(start,end);
    return new SummaryGroupingPolicy(summaryPolicy).aggregate(summaryPolicy.aggregate(records,start,end)).stream().map(segment -> {
      var ids=new HashSet<>(segment.evidence().stream().map(ActivityRecord::id).toList());
      var fineIds=fine.stream().filter(s->s.recordIds().stream().anyMatch(ids::contains)).map(ActivitySession::id).toList();
      return new SummarySegment(segment.startedAt(),segment.endedAt(),segment.observedSeconds(),segment.roles(),segment.evidence(),fineIds,segment.theme(),segment.primaryCategories());
    }).toList();
  }
  /** Display-only semantic projection; raw records and persisted fine sessions are never rewritten. */
  public String summary(String day) {
    return new ActivitySummaryFormatter(clock.getZone()).format(summarySegments(day));
  }
  public List<TrendSummarySegment> trendSegments(String day) {
    var date=date(day);var start=date.atStartOfDay(clock.getZone()).toInstant();
    var end=date.plusDays(1).atStartOfDay(clock.getZone()).toInstant();
    return trendBetween(start,end);
  }
  private List<TrendSummarySegment> trendBetween(Instant start,Instant end) {
    if(start.equals(end)) return List.of();
    var fine=store.findBetween(start,end);
    return new TrendSummaryPolicy(clock.getZone(),summaryPolicy.minimumConfidence()).aggregate(summaryBetween(start,end,fine)).stream()
        .map(s->s.withFineSessions(fine)).toList();
  }
  public String trendSummary(String day) {
    // Freeze once so date resolution and range building cannot straddle midnight.
    var snapshot=Clock.fixed(clock.instant(),clock.getZone());
    var date=new ActivityDateArgumentResolver(snapshot).resolve(day);
    var range=ActivityQueryRange.forDate(date,snapshot);
    var segments=range.fromInclusive().equals(range.toExclusive())?List.<SummarySegment>of():summaryBetween(range.fromInclusive(),range.toExclusive());
    return dailySummaries.summarize(date,range,clock.getZone(),summaryPolicy.minimumConfidence(),segments);
  }
}
