package dev.mikoto2000.rei.activity.behavior;

import dev.mikoto2000.rei.activity.*;
import dev.mikoto2000.rei.topic.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** A single bounded background job. Notification reservations survive restarts and generation failures. */
public final class BehaviorService {
  private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(BehaviorService.class);
  private final BehaviorProperties config;private final ActivityProperties activity;
  private final ActivityCapture capture;private final ActivityStore timeline;private final BehaviorStateStore persistence;
  private final BehaviorEvaluator evaluator;private final BehaviorNotificationPolicy policy;
  private final BehaviorMessageGenerator generator;private final AgentMessagePublisher publisher;private final AgentActivityTracker tracker;private final Clock clock;
  private final AtomicBoolean running=new AtomicBoolean();
  private Boolean override;private long generation;private BehaviorState state;private volatile BehaviorAssessment assessment;
  private volatile String suppression="NOT_EVALUATED";
  public BehaviorService(BehaviorProperties config,ActivityProperties activity,ActivityCapture capture,ActivityStore timeline,BehaviorStateStore persistence,
      BehaviorMessageGenerator generator,AgentMessagePublisher publisher,AgentActivityTracker tracker,Clock clock) {
    config.validate();this.config=config;this.activity=activity;this.capture=capture;this.timeline=timeline;this.persistence=persistence;
    this.generator=generator;this.publisher=publisher;this.tracker=tracker;this.clock=clock;
    evaluator=new BehaviorEvaluator(config,activity.getPrimaryConfidenceThreshold());policy=new BehaviorNotificationPolicy(config);
  }
  public synchronized boolean enabled() {return override==null?config.isEnabled():override;}
  public synchronized void setEnabled(boolean value) {override=value;generation++;}
  private synchronized boolean allowed(long token) {return enabled() && activity.isEnabled() && !capture.isPaused() && token==generation;}
  public BehaviorAssessment evaluate() {
    Instant now=clock.instant(),start=now.minusSeconds(config.getHistoryMinutes()*60L);
    var fine=timeline.findBetween(start,now);var raw=timeline.findRecordsBetween(start,now);
    var result=evaluator.evaluate(fine,raw,now);assessment=result;return result;
  }
  public void tick() {
    if(!running.compareAndSet(false,true)) return;
    BehaviorAssessment attempted=null;
    try {
      long token; synchronized(this) {token=generation;}
      if(!allowed(token)) return;
      var a=evaluate();BehaviorNotification notification;long activityVersion;
      synchronized(this) {
        if(!allowed(token)) return;
        if(state==null) state=Objects.requireNonNull(persistence.load());
        var decision=policy.decide(a,state,true,tracker.isAgentBusy());suppression=decision.suppression().name();
        var next=decision.allowed()?decision.state().notified(a):decision.state();
        if(!next.equals(state)) {persistence.save(next,a);state=next;}
        if(!decision.allowed()) {recordEvent(a,clock.instant(),BehaviorTimelineEvent.Outcome.SUPPRESSED,suppression);return;}
        notification=new BehaviorNotification(state.episodeId(),a);activityVersion=tracker.activityVersion();
        attempted=a;
      }
      // No monitor is held during LLM inference. Turning off or starting chat invalidates the delivery.
      String message=generator.generate(notification);
      if(!allowed(token)) {recordEvent(a,clock.instant(),BehaviorTimelineEvent.Outcome.SUPPRESSED,"CONTEXT_CHANGED");return;}
      var current=evaluate();
      synchronized(this) {
        if(!allowed(token) || tracker.isAgentBusy() || tracker.activityVersion()!=activityVersion
            || !current.activeEntertainment() || !current.episodeQualified() || current.severity().ordinal()<a.severity().ordinal()
            || current.recoveredAt()!=null && (a.recoveredAt()==null || current.recoveredAt().isAfter(a.recoveredAt()))) {suppression="CONTEXT_CHANGED";recordEvent(a,clock.instant(),BehaviorTimelineEvent.Outcome.SUPPRESSED,suppression);return;}
        if(message==null || message.isBlank()) throw new IllegalStateException("Empty behavior message");
        var emittedAt=clock.instant();
        publisher.publish(new AgentMessage(UUID.randomUUID().toString(),"assistant",message,MessageOrigin.BEHAVIOR,emittedAt));
        suppression="DELIVERED";
        recordEvent(a,emittedAt,BehaviorTimelineEvent.Outcome.EMITTED,suppression);
      }
    } catch(Exception error) {suppression="FAILED";if(attempted!=null)recordEvent(attempted,clock.instant(),BehaviorTimelineEvent.Outcome.FAILED,suppression);log.warn("Behavior evaluation/notification failed ({})",error.getClass().getSimpleName());}
    finally {running.set(false);}
  }
  private void recordEvent(BehaviorAssessment a,Instant at,BehaviorTimelineEvent.Outcome outcome,String reason) {
    if(a.severity()==BehaviorSeverity.NONE)return;
    try {persistence.appendEvent(new BehaviorTimelineEvent(UUID.randomUUID().toString(),at,a.severity(),a.reason(),outcome,reason,a.continuousEntertainmentSeconds(),a.windows()));}
    catch(Exception e){log.warn("Behavior timeline history unavailable; delivery policy unchanged");}
  }
  public synchronized String status() {
    if(state==null) state=Objects.requireNonNull(persistence.load());
    var a=assessment;var until=policy.cooldownUntil(state);
    return "Behavior enabled="+enabled()+"; severity="+(a==null?state.currentSeverity()+" (保存値)":a.severity())
        +"; continuousObservedMinutes="+(a==null?0:a.continuousEntertainmentSeconds()/60.0)
        +"; cooldownUntil="+(until==null?"none":until)+"; notification="+suppression;
  }
}
