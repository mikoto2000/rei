package dev.mikoto2000.rei.activity;

import dev.mikoto2000.rei.event.*;
import java.time.*;
import java.util.*;

/** Retains only bounded tool kinds/project IDs, never command arguments, tool results, or conversation text. */
public final class ActivityAgentEvidenceSource implements ActivityEvidenceSource,AgentEventListener,AutoCloseable {
  private final Deque<ActivityEvidence.RecentEvent> recent=new ArrayDeque<>();
  private final AgentEventBus.Subscription subscription;
  public ActivityAgentEvidenceSource(AgentEventBus bus) {subscription=bus==null?null:bus.subscribe(this);}
  @Override public synchronized void onEvent(AgentEvent event) {
    if(event==null || !(event.payload() instanceof ToolCompletedPayload p) || p.toolName()==null)return;
    String kind=switch(p.toolName()) {case "runCommand","executeExternalProgram"->"SHELL";case "writeMultiFile","applyTextDiff"->"FILE_EDIT";default->null;};
    if(kind==null || event.projectId()==null)return;
    recent.addLast(new ActivityEvidence.RecentEvent(event.timestamp(),event.projectId(),kind));while(recent.size()>16)recent.removeFirst();
  }
  @Override public synchronized Contribution collect(Instant at) {
    recent.removeIf(e->e.at().isBefore(at.minusSeconds(120)));
    return new Contribution("","",recent.stream().filter(e->!e.at().isAfter(at)).toList());
  }
  @Override public void close() {if(subscription!=null)subscription.unsubscribe();}
}
