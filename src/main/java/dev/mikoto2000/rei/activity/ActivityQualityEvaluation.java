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
  private static void bounded(String value,int max){if(value==null||value.isBlank()||value.length()>max)throw new IllegalArgumentException("Bounded nonblank fixture value required");}
}
