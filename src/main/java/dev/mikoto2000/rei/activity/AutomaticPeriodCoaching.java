package dev.mikoto2000.rei.activity;

import dev.mikoto2000.rei.topic.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.*;

/** Opt-in, at most two closed-period assessments and one publication per tick. No model calls. */
public final class AutomaticPeriodCoaching {
  private final ActivityProperties properties;
  private final PeriodCoachingService coaching;
  private final AgentMessagePublisher publisher;
  private final AgentActivityTracker tracker;
  private final BooleanSupplier paused;
  private final Clock clock;
  private final Supplier<String> ids;
  private final AtomicBoolean running=new AtomicBoolean();
  private volatile String status="NOT_EVALUATED";
  public AutomaticPeriodCoaching(ActivityProperties properties,PeriodCoachingService coaching,AgentMessagePublisher publisher,
      AgentActivityTracker tracker,BooleanSupplier paused,Clock clock) {
    this(properties,coaching,publisher,tracker,paused,clock,()->UUID.randomUUID().toString());
  }
  public AutomaticPeriodCoaching(ActivityProperties properties,PeriodCoachingService coaching,AgentMessagePublisher publisher,
      AgentActivityTracker tracker,BooleanSupplier paused,Clock clock,Supplier<String> ids) {
    properties.validate();this.properties=properties;this.coaching=coaching;this.publisher=publisher;
    this.tracker=tracker;this.paused=paused;this.clock=clock;this.ids=ids;
  }
  public boolean enabled(){return properties.isEnabled() && properties.getCoaching().isAutomaticEnabled();}
  public String status(){return status;}
  private boolean allowed(){return enabled() && publisher!=null && tracker!=null && !paused.getAsBoolean() && !tracker.isAgentBusy() && !Thread.currentThread().isInterrupted();}
  public void tick() {
    if(!running.compareAndSet(false,true))return;
    try {
      if(!allowed()){status=enabled()?"UNAVAILABLE_OR_BUSY":"DISABLED";return;}
      if(!coaching.status().settings().enabled()){status="DISABLED";return;}
      long version=tracker.activityVersion();status="NO_PERIODS";
      // A new month gets the first opportunity; weekly/manual advice share the same cooldown.
      for(var period:List.of(ActivityPeriodAnalysis.Period.MONTH,ActivityPeriodAnalysis.Period.WEEK)) {
        if(period==ActivityPeriodAnalysis.Period.MONTH?!properties.getCoaching().isMonthlyEnabled():!properties.getCoaching().isWeeklyEnabled())continue;
        if(!allowed() || tracker.activityVersion()!=version){status="CONTEXT_CHANGED";return;}
        var prepared=coaching.prepare(period,null);status=prepared.reason();
        if(!prepared.advice())continue;
        if(!allowed() || tracker.activityVersion()!=version){status="CONTEXT_CHANGED";return;}
        status=coaching.reserve(prepared);
        if(!status.equals("RESERVED"))continue;
        // Never release the receipt: failure/context change after claim is not safe to replay.
        if(!allowed() || tracker.activityVersion()!=version || !coaching.current(prepared)){status="CONTEXT_CHANGED";return;}
        publisher.publish(new AgentMessage(ids.get(),"assistant","自動"+prepared.message(),MessageOrigin.BEHAVIOR,clock.instant(),
            Map.of("severity","NOTICE","triggerType","PERIOD_COACHING_"+period)));
        status="DELIVERED";return;
      }
    }catch(RuntimeException error) {
      dev.mikoto2000.rei.core.chat.RunCancellation.propagate(error);
      status="FAILED";org.slf4j.LoggerFactory.getLogger(getClass()).warn("Automatic period coaching failed ({})",error.getClass().getSimpleName());
    }finally{running.set(false);}
  }
}
