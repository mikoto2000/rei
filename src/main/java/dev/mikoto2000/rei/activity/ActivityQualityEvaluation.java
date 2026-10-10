package dev.mikoto2000.rei.activity;

import java.time.*;
import java.util.*;
import dev.mikoto2000.rei.core.chat.RunCancellation;

/** Anonymous evaluation of existing projections. Never promotes an inference into an observed fact. */
public final class ActivityQualityEvaluation {
  private ActivityQualityEvaluation(){}
  public enum Origin { OBSERVED, INFERRED, USER_CONFIRMED }
  public record ConfirmedFact(String key,String value,String confirmationId){public ConfirmedFact{bounded(key,128);bounded(value,1024);bounded(confirmationId,128);}}
  public record Fact(String key,String value,Origin origin,String evidenceId){}
  public record Case(String id,String quality,String process,String title,double confidence,long observedSeconds,
      List<ActivityRecord.Activity> candidates,String expectedProject,String expectedTheme,boolean expectedAdvice,String expectedReason,List<ConfirmedFact> confirmedFacts){
    public Case{bounded(id,128);bounded(quality,128);bounded(process,128);if(title==null||title.length()>1024)throw new IllegalArgumentException("Bounded title required");if(!Double.isFinite(confidence)||confidence<0||confidence>1||observedSeconds<0||observedSeconds>86400)throw new IllegalArgumentException("Bounded observation required");candidates=List.copyOf(candidates);confirmedFacts=List.copyOf(confirmedFacts);if(candidates.size()>16||confirmedFacts.size()>16)throw new IllegalArgumentException("Bounded candidates/facts required");for(var candidate:candidates){bounded(candidate.type(),128);bounded(candidate.application(),128);if(candidate.projectCandidate()==null||candidate.projectCandidate().length()>128||candidate.contentTitle()==null||candidate.contentTitle().length()>1024||candidate.service()==null||candidate.service().length()>128)throw new IllegalArgumentException("Bounded candidate required");}if(expectedProject==null||expectedProject.length()>128)throw new IllegalArgumentException("Expected project required");bounded(expectedTheme,128);bounded(expectedReason,128);}
  }
  public record Row(String id,String quality,String project,String theme,boolean projectMatches,boolean themeMatches,
      long observedSeconds,long unobservedSeconds,double coverage,boolean advice,String reason,List<Fact> facts,List<String> violations){}
  public record Report(String provider,List<Row> rows,double projectAccuracy,double themeAccuracy,int structuralFailures,boolean truthVerified){}
  public static Report evaluate(List<Case> fixtures){
    fixtures=List.copyOf(fixtures);if(fixtures.isEmpty()||fixtures.size()>128||fixtures.stream().map(Case::id).distinct().count()!=fixtures.size())throw new IllegalArgumentException("Unique bounded fixtures required");
    var start=Instant.parse("2026-09-21T00:00:00Z");var clock=Clock.fixed(start.plusSeconds(1209600),ZoneOffset.UTC);var analysis=new ActivityPeriodAnalysis(clock,.5);var range=analysis.range(ActivityPeriodAnalysis.Period.WEEK,LocalDate.of(2026,9,21));var previousRange=analysis.previous(range);
    var settings=new PeriodCoaching.Settings(true,Set.of("development"),.9,60,.005,.25,7);var rows=new ArrayList<Row>();long deadline=System.nanoTime()+Duration.ofSeconds(5).toNanos();
    for(var fixture:fixtures){RunCancellation.propagate(null);if(System.nanoTime()>deadline)throw new IllegalStateException("Activity evaluation deadline exceeded");
      var record=new ActivityRecord(fixture.id(),start.plusSeconds(3600),fixture.observedSeconds(),List.of(),new ForegroundWindow(fixture.process(),1,fixture.title(),"anonymous-window"),new ActivityRecord.Inference("anonymous candidates",fixture.candidates()),fixture.confidence(),List.of(),0,false,"anonymous");
      var roles=new ActivityRolePolicy().classify(record);String project=roles.primary()==null?"":roles.primary().projectCandidate();String theme=SummaryGroupingPolicy.theme(roles);
      var current=analysis.aggregate(range,List.of(record));var priorRecord=new ActivityRecord("prior",previousRange.fromInclusive().plusSeconds(3600),7200,List.of(),new ForegroundWindow("Terminal",1,"project-alpha","prior-window"),new ActivityRecord.Inference("anonymous baseline",List.of(new ActivityRecord.Activity("m1","development","Terminal","","","project-alpha"))),.9,List.of(),0,false,"prior");var previous=analysis.aggregate(previousRange,List.of(priorRecord));var assessment=new PeriodCoaching().evaluate(settings,current,previous);
      var violations=new ArrayList<String>();if(assessment.advice()!=fixture.expectedAdvice())violations.add("ADVICE_EXPECTATION");if(!assessment.reason().equals(fixture.expectedReason()))violations.add("REASON_EXPECTATION");long span=current.observedSeconds()+current.unobservedSeconds();double coverage=span==0?0:(double)current.observedSeconds()/span;
      if(assessment.advice()&&(coverage<settings.minimumCoverage()||current.observedSeconds()<settings.minimumObservedMinutes()*60L||roles.primary()==null))violations.add("UNGROUNDED_ADVICE");if(!Double.isFinite(assessment.currentShare())||assessment.currentShare()<0||assessment.currentShare()>1)violations.add("INVALID_SHARE");
      var facts=new ArrayList<Fact>();facts.add(new Fact("foregroundProcess",fixture.process(),Origin.OBSERVED,record.id()));facts.add(new Fact("foregroundTitle",fixture.title(),Origin.OBSERVED,record.id()));facts.add(new Fact("projectCandidate",project,Origin.INFERRED,record.id()));facts.add(new Fact("themeCandidate",theme,Origin.INFERRED,record.id()));facts.add(new Fact("observedDurationEstimateSeconds",Long.toString(current.observedSeconds()),Origin.INFERRED,record.id()));for(var confirmed:fixture.confirmedFacts())facts.add(new Fact(confirmed.key(),confirmed.value(),Origin.USER_CONFIRMED,confirmed.confirmationId()));
      rows.add(new Row(fixture.id(),fixture.quality(),project,theme,project.equals(fixture.expectedProject()),theme.equals(fixture.expectedTheme()),current.observedSeconds(),current.unobservedSeconds(),coverage,assessment.advice(),assessment.reason(),List.copyOf(facts),List.copyOf(violations)));
    }
    return new Report("ActivityRolePolicy/ActivityPeriodAnalysis/PeriodCoaching",List.copyOf(rows),(double)rows.stream().filter(Row::projectMatches).count()/rows.size(),(double)rows.stream().filter(Row::themeMatches).count()/rows.size(),rows.stream().mapToInt(row->row.violations().size()).sum(),false);
  }
  public record DesktopRow(String mode,long observedSeconds,int contextCandidates,int primaryChanges,
      long visionCalls,long queueWaitMillis,long processingMillis,long freshnessMillis,String temporalInference) {}
  public record DesktopReport(List<DesktopRow> rows,boolean truthVerified,String limitation) {}
  /** Replay already-analyzed observations, without calls to Vision or disk writes. */
  public static DesktopReport compareDesktop(List<ActivityRecord> records) {
    if(records.isEmpty() || records.size()>120 || records.stream().map(ActivityRecord::id).distinct().count()!=records.size())throw new IllegalArgumentException("Bounded desktop fixtures required");
    var rows=new ArrayList<DesktopRow>();long duration=records.stream().mapToLong(ActivityRecord::durationEstimate).sum();
    int contexts=0;long foregroundCalls=0,backgroundCalls=0,wait=0,processing=0,freshness=0,foregroundWait=0,foregroundProcessing=0,foregroundFreshness=0;
    for(var record:records) {
      var v=VisionDiagnostics.of(record.detection());
      for(var result:new VisionDiagnostics.Result[]{v.foreground(),v.background()})if(result!=null && result.timing()!=null && result.state()!=VisionDiagnostics.State.NOT_ATTEMPTED && result.timing().startedAt()!=null) {
        var t=result.timing();if(t.startedAt()!=null){if(result==v.foreground())foregroundCalls++;else backgroundCalls++;}
        if(t.imageCapturedAt()!=null && t.startedAt()!=null){long value=Math.max(0,Duration.between(t.imageCapturedAt(),t.startedAt()).toMillis());wait+=value;if(result==v.foreground())foregroundWait+=value;}
        if(t.startedAt()!=null && t.completedAt()!=null){long value=Math.max(0,Duration.between(t.startedAt(),t.completedAt()).toMillis());processing+=value;if(result==v.foreground())foregroundProcessing+=value;}
        if(t.completedAt()!=null){long value=Math.max(0,Duration.between(record.capturedAt(),t.completedAt()).toMillis());freshness=Math.max(freshness,value);if(result==v.foreground())foregroundFreshness=Math.max(foregroundFreshness,value);}
      }
      if(v.background()!=null && v.background().context()!=null)contexts+=v.background().context().candidates().size();
    }
    rows.add(new DesktopRow("A",duration,0,0,foregroundCalls,foregroundWait,foregroundProcessing,foregroundFreshness,""));
    rows.add(new DesktopRow("B",duration,contexts,0,foregroundCalls+backgroundCalls,wait,processing,freshness,""));
    var first=records.stream().map(ActivityRecord::capturedAt).min(Comparator.naturalOrder()).orElseThrow();
    var last=records.stream().map(ActivityRecord::capturedAt).max(Comparator.naturalOrder()).orElseThrow();
    rows.add(new DesktopRow("C",duration,contexts,0,foregroundCalls+backgroundCalls,wait,processing,freshness,
        TemporalActivityEvidence.build(records,List.of(),first,last).ruleInference().inferredActivity()));
    return new DesktopReport(List.copyOf(rows),false,"Replay is structural evaluation. Accuracy requires independent manual labels; CPU/RSS/SSD I/O are not measured.");
  }
  private static void bounded(String value,int max){if(value==null||value.isBlank()||value.length()>max)throw new IllegalArgumentException("Bounded nonblank fixture value required");}
}
