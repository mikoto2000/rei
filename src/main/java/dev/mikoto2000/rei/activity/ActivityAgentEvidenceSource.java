package dev.mikoto2000.rei.activity;

import dev.mikoto2000.rei.event.*;
import java.time.*;
import java.util.*;

/** Legacy OS contribution retains IDs only; the optional temporal supplement uses bounded existing result summaries. */
public final class ActivityAgentEvidenceSource implements ActivityEvidenceSource,AgentEventListener,AutoCloseable {
  private final Deque<ActivityEvidence.RecentEvent> recent=new ArrayDeque<>();
  private final Deque<WorkActivityInference.Execution> executions=new ArrayDeque<>();
  private final AgentEventBus.Subscription subscription;
  private final java.util.function.BooleanSupplier temporalEnabled;
  public ActivityAgentEvidenceSource(AgentEventBus bus){this(bus,()->true);}
  public ActivityAgentEvidenceSource(AgentEventBus bus,java.util.function.BooleanSupplier temporalEnabled) {this.temporalEnabled=temporalEnabled;subscription=bus==null?null:bus.subscribe(this);}
  @Override public synchronized void onEvent(AgentEvent event) {
    if(event==null || event.projectId()==null)return;
    String tool,summary,outcome;
    boolean temporal=temporalEnabled.getAsBoolean();
    if(event.payload() instanceof ToolCompletedPayload p){tool=p.toolName();summary=temporal?TemporalActivityEvidence.clean(p.resultSummary()):"";outcome="COMPLETED";}
    else if(event.payload() instanceof ToolFailedPayload p){tool=p.toolName();summary="";outcome="FAILED";}
    else return;
    if(tool==null)return;
    String kind=switch(tool) {case "runCommand","executeExternalProgram"->"SHELL";case "writeMultiFile","applyTextDiff"->"FILE_EDIT";default->null;};
    if(kind==null || event.projectId()==null)return;
    if(temporal){executions.addLast(new WorkActivityInference.Execution(event.id(),event.timestamp(),event.projectId(),kind,"REI",outcome,summary,event.sessionId(),event.turnId(),event.runId()));while(executions.size()>64)executions.removeFirst();}
    else executions.clear();
    if("COMPLETED".equals(outcome)){recent.addLast(new ActivityEvidence.RecentEvent(event.timestamp(),event.projectId(),kind,event.id(),event.sessionId(),event.turnId(),event.runId()));while(recent.size()>16)recent.removeFirst();}
  }
  @Override public synchronized Contribution collect(Instant at) {
    recent.removeIf(e->e.at().isBefore(at.minusSeconds(120)));
    return new Contribution("","",recent.stream().filter(e->!e.at().isAfter(at)).toList());
  }
  public synchronized List<WorkActivityInference.Execution> executions(Instant at,int windowSeconds) {
    if(!temporalEnabled.getAsBoolean()){executions.clear();return List.of();}
    executions.removeIf(e->e.at().isBefore(at.minusSeconds(600)));
    return executions.stream().filter(e->!e.at().isBefore(at.minusSeconds(windowSeconds)) && !e.at().isAfter(at)).toList();
  }
  @Override public void close() {if(subscription!=null)subscription.unsubscribe();}
}
