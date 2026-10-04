package dev.mikoto2000.rei.attention;

import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import jakarta.annotation.*;
import dev.mikoto2000.rei.event.*;
import dev.mikoto2000.rei.temporal.MonotonicTimeSource;

/** Derives attention only from owned execution facts, without interpreting model text. */
@Component
public class AttentionService {
  private record Run(String project,String session,String run) {}
  private final AttentionRepository inbox;
  private final AgentEventBus bus;
  private final AgentEventPublisher publisher;
  private final Clock clock;
  private final MonotonicTimeSource time;
  private final Map<Run,Long> waiting=new LinkedHashMap<>();
  private final Set<Run> finished=new LinkedHashSet<>();
  private AgentEventBus.Subscription subscription;
  @Autowired
  public AttentionService(AttentionRepository inbox,AgentEventBus bus,AgentEventPublisher publisher,Clock clock,MonotonicTimeSource time) {
    this.inbox=inbox;this.bus=bus;this.publisher=publisher;this.clock=clock;this.time=time;
  }
  @PostConstruct public synchronized void start(){if(subscription==null)subscription=bus.subscribe(this::observe);}
  @PreDestroy public synchronized void close(){if(subscription!=null){subscription.unsubscribe();subscription=null;}waiting.clear();finished.clear();}
  private synchronized void observe(AgentEvent event) {
    if(event.projectId()==null||event.sessionId()==null)return;
    if(event.type()==AgentEventType.DEPENDENCY_UPDATED||event.type()==AgentEventType.DEPENDENCY_COMPLETED
        ||event.type()==AgentEventType.DEPENDENCY_FAILED||event.type()==AgentEventType.DEPENDENCY_CANCELLED){dependency(event);return;}
    if(event.runId()==null)return;
    var run=new Run(event.projectId(),event.sessionId(),event.runId());
    switch(event.type()) {
      case GOAL_UPDATED -> {
        var payload=(GoalLifecyclePayload)event.payload();
        if(payload.status().equals("BLOCKED")||payload.status().equals("FAILED"))
          notify(event,"GOAL_STOPPED",payload.goalId(),"A Goal stopped before its criterion was verified. Inspect /goal show and /goal history before retrying.");
      }
      case TOOL_FAILED -> {
        var payload=(ToolFailedPayload)event.payload();if(payload.error()==null)return;
        switch(payload.error().errorType()) {
          case "PermissionRequired" -> notify(event,"APPROVAL_REQUIRED",bounded(payload.toolName()),"Tool approval is required. Review /approval list, decide explicitly, then resume or retry.");
          case "PermissionDenied" -> notify(event,"POLICY_DENIED",bounded(payload.toolName()),"Policy denied a Tool action. Review the task and policy; acknowledging this item does not grant permission.");
          case null,default -> { }
        }
      }
      case STAGNATION_UPDATED -> {
        var payload=(ExecutionProgressPayload)event.payload();
        if(!"waiting_for_dependency".equals(payload.reason())){waiting.remove(run);return;}
        if(finished.contains(run))return;
        long now=time.nanoTime();long since=waiting.computeIfAbsent(run,ignored->now);
        if(waiting.size()>1024)waiting.remove(waiting.keySet().iterator().next());
        if(now-since>=Duration.ofMinutes(2).toNanos())notify(event,"LONG_WAIT","dependency","A dependency has remained waiting for at least two minutes. Inspect the process and task before intervening.");
      }
      case PROGRESS_DETECTED,STAGNATION_RECOVERED -> waiting.remove(run);
      case STAGNATION_STOPPED -> {
        waiting.remove(run);notify(event,"STAGNATION_STOPPED","stagnation","The run stopped after repeated stagnation. Inspect its result and checkpoint before resuming.");
      }
      case AGENT_RUN_COMPLETED,AGENT_RUN_FAILED,AGENT_RUN_CANCELLED -> {
        if(event.type()==AgentEventType.AGENT_RUN_COMPLETED&&event.payload() instanceof AgentRunCompletedPayload payload&&run.run().equals(payload.runId()))
          notify(event,"RUN_COMPLETED","terminal","The Agent Run ended normally. Inspect its result; declared Goal criteria still require independent verification.");
        else if(event.type()==AgentEventType.AGENT_RUN_FAILED&&event.payload() instanceof AgentRunFailedPayload payload&&run.run().equals(payload.runId()))
          notify(event,"RUN_FAILED","terminal","The Agent Run failed. Inspect its result and checkpoint before retrying; acknowledgement does not resume it.");
        waiting.remove(run);finished.add(run);if(finished.size()>1024)finished.remove(finished.iterator().next());
      }
      default -> { }
    }
  }
  private void dependency(AgentEvent event) {
    if(!(event.payload() instanceof DependencyStatusPayload payload)||event.runId()!=null||payload.revision()<0
        ||payload.dependencyId()==null||payload.dependencyId().isBlank()||payload.dependencyId().length()>128
        ||!payload.dependencyId().equals(event.correlationId()))return;
    String kind=null,message=null;
    if(event.type()==AgentEventType.DEPENDENCY_COMPLETED&&"COMPLETED".equals(payload.state())) {
      kind="DEPENDENCY_COMPLETED";message="The declared dependency condition was observed. Inspect /dependency show and history before continuing the task.";
    }else if(event.type()==AgentEventType.DEPENDENCY_FAILED&&"FAILED".equals(payload.state())) {
      kind="DEPENDENCY_FAILED";message="A dependency failed or its deadline expired. Inspect /dependency show and history before retrying.";
    }else if(event.type()==AgentEventType.DEPENDENCY_UPDATED&&"USER_ANSWER".equals(payload.kind())
        &&"WAITING".equals(payload.state())&&("user_answer_waiting".equals(payload.reason())||"watch_pending".equals(payload.reason()))) {
      kind="DECISION_REQUIRED";message="A task is waiting for a human answer. Review /dependency show and use /dependency answer explicitly; acknowledgement does not answer or grant consent.";
    }
    if(kind!=null)publishAttention(event,inbox.createDependency(event,kind,payload.dependencyId(),message),kind,message);
  }
  private void publishAttention(AgentEvent source,Optional<AttentionRepository.Item> item,String kind,String message) {
    item.ifPresent(saved->publisher.publish(new AgentEvent(UUID.randomUUID().toString(),0,clock.instant(),
        AgentEventType.ATTENTION_REQUIRED,1,source.sessionId(),source.turnId(),source.runId(),saved.id(),source.id(),
        new AttentionRequiredPayload(saved.id(),kind,message),source.projectId())));
  }
  private void notify(AgentEvent source,String kind,String reference,String message) {
    publishAttention(source,inbox.create(source,kind,reference,message),kind,message);
  }
  private static String bounded(String value){String safe=CredentialRedactor.redact(value==null?"unknown":value);return safe.substring(0,Math.min(128,safe.length()));}
}
