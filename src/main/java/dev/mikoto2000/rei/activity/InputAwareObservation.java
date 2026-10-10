package dev.mikoto2000.rei.activity;

import java.time.*;
import java.util.Objects;
import dev.mikoto2000.rei.activity.DesktopActivityObserver.Lightweight;

/** Successful persistence owns the baseline. All state transitions share one monitor. */
final class InputAwareObservation {
  private final Clock clock;
  private final int interval,maximum;
  private Lightweight checked,baseline,attempt;
  private Instant checkedAt,savedAt;
  private boolean busy,forced=true,saved;
  private long revision,attemptRevision;
  InputAwareObservation(Clock clock,int interval,int maximum) {this.clock=clock;this.interval=interval;this.maximum=maximum;}
  synchronized void force(){forced=true;revision++;}
  synchronized boolean begin(Lightweight current) {
    if(busy){if(changed(attempt,current))force();return false;}
    var now=clock.instant();
    boolean discontinuity=checkedAt!=null && (now.isBefore(checkedAt)
        || Duration.between(checkedAt,now).toMillis()>interval*2000L
        || current!=null && checked!=null && (current.uptimeMillis()<checked.uptimeMillis()
        || current.workingMillis()<checked.workingMillis()
        || (current.uptimeMillis()-checked.uptimeMillis())-(current.workingMillis()-checked.workingMillis())>1000
        || Math.abs((current.uptimeMillis()-checked.uptimeMillis())-Duration.between(checkedAt,now).toMillis())>interval*2000L));
    if(discontinuity)force();
    checked=current;checkedAt=now;
    if(current!=null && current.locked()){force();return false;}
    boolean uncertain=current==null || !current.reliable() || current.windowId()==null
        || current.uptimeMillis()<0 || ((current.uptimeMillis()-current.lastInput())&0xffffffffL)>0x7fffffffL;
    boolean due=forced || uncertain || baseline==null || changed(current,baseline) || savedAt==null
        || now.isBefore(savedAt) || Duration.between(savedAt,now).getSeconds()>=maximum;
    if(!due)return false;
    busy=true;saved=false;attempt=current;attemptRevision=revision;return true;
  }
  synchronized void saved(){if(busy)saved=true;}
  synchronized boolean lightweightAttempt(){return busy && attempt!=null;}
  synchronized void end(Lightweight current) {
    if(!busy)return;
    if(saved && attemptRevision==revision){baseline=attempt;savedAt=clock.instant();forced=changed(attempt,current);}
    else forced=true;
    busy=false;attempt=null;
  }
  private static boolean changed(Lightweight a,Lightweight b) {
    return a==null || b==null || !a.reliable() || !b.reliable() || a.lastInput()!=b.lastInput()
        || !Objects.equals(a.windowId(),b.windowId()) || !Objects.equals(a.desktop(),b.desktop()) || a.locked()!=b.locked();
  }
}
