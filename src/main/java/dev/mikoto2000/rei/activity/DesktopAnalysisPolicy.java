package dev.mikoto2000.rei.activity;

import java.time.*;
import java.util.*;

/** Bounded low-priority scheduling policy; no timers or screenshot retention. */
final class DesktopAnalysisPolicy {
  private final ActivityProperties p;
  private final Deque<Instant> starts=new ArrayDeque<>(),executions=new ArrayDeque<>(),switches=new ArrayDeque<>();
  private Instant last,lastExecution;private String key,candidate,window;
  DesktopAnalysisPolicy(ActivityProperties p){this.p=p;}
  void reset(){starts.clear();executions.clear();switches.clear();last=null;lastExecution=null;candidate=null;key=null;window=null;}
  void window(Instant at,String id) {
    if(window!=null && !Objects.equals(window,id))switches.addLast(at);window=id;
    while(!switches.isEmpty() && !switches.getFirst().isAfter(at.minusSeconds(60)))switches.removeFirst();
    while(switches.size()>4)switches.removeFirst();
  }
  boolean due(Instant at,boolean idle,String context) {
    while(!starts.isEmpty() && !starts.getFirst().isAfter(at.minusSeconds(3600)))starts.removeFirst();
    boolean changed=key!=null && !Objects.equals(key,context);candidate=context;
    if(!p.getDetection().isBackgroundFullScreenEnabled() || idle || starts.size()>=6)return false;
    if(last==null)return true;
    if(at.isBefore(last.plusSeconds(60)))return false;
    return !at.isBefore(last.plusSeconds(Math.max(60,p.getBackgroundAnalysisIntervalSeconds()))) || changed || switches.size()>=3;
  }
  boolean executionAllowed(Instant at) {
    while(!executions.isEmpty() && !executions.getFirst().isAfter(at.minusSeconds(3600)))executions.removeFirst();
    return executions.size()<6 && (lastExecution==null || !at.isBefore(lastExecution.plusSeconds(60)));
  }
  void executed(Instant at){lastExecution=at;executions.addLast(at);}
  void started(Instant at){last=at;key=candidate;starts.addLast(at);switches.clear();}
}
