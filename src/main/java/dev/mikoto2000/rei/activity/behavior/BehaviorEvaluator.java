package dev.mikoto2000.rei.activity.behavior;

import dev.mikoto2000.rei.activity.*;
import java.time.*;
import java.util.*;

/** Pure evaluation of fine-session evidence. Summary projections and external context are not inputs. */
public final class BehaviorEvaluator {
  private final BehaviorProperties config;
  private final ActivityRolePolicy roles;
  private record Sample(Instant start,Instant end,String category,String service,double confidence,String continuity) {
    long seconds() {return Duration.between(start,end).getSeconds();}
    boolean eligible() {return !Set.of("unknown","other","idle").contains(category);}
  }
  public BehaviorEvaluator(BehaviorProperties config,double minimumConfidence) {config.validate();this.config=config;roles=new ActivityRolePolicy(minimumConfidence);}
  public BehaviorAssessment evaluate(List<ActivitySession> sessions,List<ActivityRecord> records,Instant now) {
    var samples=samples(sessions,records,now);
    long continuous=0,interruption=0,noise=0;Instant recovery=null,episode=null;
    Sample previous=null;
    long resetSeconds=config.getInterruption().getResetAfterWorkMinutes()*60L;
    long tolerance=config.getInterruption().getNoiseToleranceSeconds();
    boolean recoveredDuringInterruption=false;
    for(var sample:samples) {
      if(previous!=null) {
        long gap=Math.max(0,Duration.between(previous.end(),sample.start()).getSeconds());
        noise+=gap;
        if(noise>tolerance || !sample.continuity().equals(previous.continuity())) {continuous=0;interruption=0;recoveredDuringInterruption=false;}
      }
      if(entertainment(sample)) {
        continuous+=sample.seconds();interruption=0;noise=0;recoveredDuringInterruption=false;
        if(episode==null) episode=sample.start();
      } else if(sample.eligible()) {
        noise=0;
        long prior=interruption;interruption+=sample.seconds();
        if(interruption>=resetSeconds) {
          continuous=0;episode=null;
          if(!recoveredDuringInterruption) {recovery=sample.start().plusSeconds(Math.max(0,resetSeconds-prior));recoveredDuringInterruption=true;}
        }
      } else {
        noise+=sample.seconds();
        if(noise>tolerance) {continuous=0;interruption=0;recoveredDuringInterruption=false;}
      }
      previous=sample;
    }
    Instant latest=previous==null?null:previous.end();
    boolean active=previous!=null && entertainment(previous) && Duration.between(latest,now).getSeconds()<=tolerance;
    if(latest!=null && Duration.between(latest,now).getSeconds()>tolerance) continuous=0;
    var shortWindow=window(samples,now,config.getWindows().getShortWindow(),BehaviorSeverity.NOTICE,null);
    var longWindow=window(samples,now,config.getWindows().getLongWindow(),BehaviorSeverity.WARNING,null);
    var continuousSeverity=continuousSeverity(continuous);
    var windowSeverity=BehaviorSeverity.max(shortWindow.severity(),longWindow.severity());
    var severity=BehaviorSeverity.max(continuousSeverity,windowSeverity);
    var reason=continuousSeverity!=BehaviorSeverity.NONE && windowSeverity!=BehaviorSeverity.NONE?BehaviorAssessment.Reason.BOTH:
        continuousSeverity!=BehaviorSeverity.NONE?BehaviorAssessment.Reason.CONTINUOUS_ENTERTAINMENT:
        windowSeverity!=BehaviorSeverity.NONE?BehaviorAssessment.Reason.ENTERTAINMENT_RATIO_HIGH:BehaviorAssessment.Reason.NONE;
    boolean qualified=continuousSeverity!=BehaviorSeverity.NONE
        || window(samples,now,config.getWindows().getShortWindow(),BehaviorSeverity.NOTICE,recovery).severity()!=BehaviorSeverity.NONE
        || window(samples,now,config.getWindows().getLongWindow(),BehaviorSeverity.WARNING,recovery).severity()!=BehaviorSeverity.NONE;
    var categories=new HashMap<String,Long>();var services=new HashMap<String,Long>();double weighted=0;long eligible=0;
    Instant contextStart=now.minusSeconds(config.getWindows().getLongWindow().getDurationMinutes()*60L);
    for(var sample:samples) {
      long seconds=overlap(sample,contextStart,now);
      if(entertainment(sample)) {eligible+=seconds;weighted+=sample.confidence()*seconds;categories.merge(sample.category(),seconds,Long::sum);if(!sample.service().isBlank()) services.merge(sample.service(),seconds,Long::sum);}
    }
    double confidence=eligible==0?0:weighted/eligible;
    return new BehaviorAssessment(severity,reason,now,continuous,List.of(shortWindow,longWindow),rank(categories),rank(services),
        confidence,latest,active,recovery,episode,qualified);
  }
  private List<Sample> samples(List<ActivitySession> sessions,List<ActivityRecord> records,Instant now) {
    var membership=new HashMap<String,List<ActivitySession>>();
    for(var session:sessions) for(String id:session.recordIds()) membership.computeIfAbsent(id,k->new ArrayList<>()).add(session);
    var sorted=records.stream().distinct().sorted(Comparator.comparing(ActivityRecord::capturedAt).thenComparing(ActivityRecord::id)).toList();
    var result=new ArrayList<Sample>();Instant covered=now.minusSeconds(config.getHistoryMinutes()*60L);
    var remaining=new HashMap<String,Long>();
    for(int i=0;i<sorted.size();i++) {
      var r=sorted.get(i);var role=roles.classify(r);
      for(var session:membership.getOrDefault(r.id(),List.of()).stream().sorted(Comparator.comparing(ActivitySession::startedAt)).toList()) {
        Instant start=max(max(r.capturedAt(),session.startedAt()),covered);
        Instant end=min(min(r.capturedAt().plusSeconds(r.durationEstimate()),session.endedAt()),now);
        if(i+1<sorted.size()) end=min(end,sorted.get(i+1).capturedAt());
        long budget=remaining.getOrDefault(session.id(),Math.max(0,session.observedSeconds()));
        end=min(end,start.plusSeconds(budget));
        if(!start.isBefore(end)) continue;
        String service=role.primary()==null?"":ActivityDisplayLabels.label(role.primary().service());
        result.add(new Sample(start,end,role.category(),service,role.confidence(),r.continuityId()));covered=end;
        remaining.put(session.id(),budget-Duration.between(start,end).getSeconds());
      }
    }
    return result;
  }
  private boolean entertainment(Sample s) {return config.getEntertainmentCategories().contains(s.category());}
  private BehaviorSeverity continuousSeverity(long seconds) {
    var c=config.getContinuous();return seconds>=c.getStrongWarningMinutes()*60L?BehaviorSeverity.STRONG_WARNING:
        seconds>=c.getWarningMinutes()*60L?BehaviorSeverity.WARNING:seconds>=c.getNoticeMinutes()*60L?BehaviorSeverity.NOTICE:BehaviorSeverity.NONE;
  }
  private BehaviorAssessment.Window window(List<Sample> samples,Instant now,BehaviorProperties.Window window,BehaviorSeverity severity,Instant floor) {
    Instant start=now.minusSeconds(window.getDurationMinutes()*60L);if(floor!=null) start=max(start,floor);
    long eligible=0,entertainment=0;
    for(var s:samples) if(s.eligible()) {long seconds=overlap(s,start,now);eligible+=seconds;if(entertainment(s)) entertainment+=seconds;}
    double ratio=eligible==0?0:(double)entertainment/eligible;
    return new BehaviorAssessment.Window(window.getDurationMinutes(),entertainment,eligible,ratio,
        eligible>=window.getMinimumObservedMinutes()*60L && ratio>=window.getRatio()?severity:BehaviorSeverity.NONE);
  }
  private static List<String> rank(Map<String,Long> weights) {return weights.entrySet().stream().filter(e->e.getValue()>0)
      .sorted(Map.Entry.<String,Long>comparingByValue().reversed().thenComparing(Map.Entry::getKey)).limit(4).map(Map.Entry::getKey).toList();}
  private static long overlap(Sample s,Instant start,Instant end) {return Math.max(0,Duration.between(max(s.start(),start),min(s.end(),end)).getSeconds());}
  private static Instant min(Instant a,Instant b) {return a.isBefore(b)?a:b;}
  private static Instant max(Instant a,Instant b) {return a.isAfter(b)?a:b;}
}
