package dev.mikoto2000.rei.reflection;

import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import jakarta.annotation.*;
import dev.mikoto2000.rei.goal.GoalRepository;
import dev.mikoto2000.rei.event.*;

/** Reflects on saved verification facts, not model statements or inferred failure causes. */
@Service
public class GoalReflectionService {
  private static final Set<String> STOPPED=Set.of("COMPLETED","BLOCKED","FAILED","PAUSED","CANCELLED","WAITING_APPROVAL");
  private final GoalRepository goals;
  private final GoalReflectionRepository reflections;
  private final AgentEventBus bus;
  private final Clock clock;
  private AgentEventBus.Subscription subscription;
  public GoalReflectionService(GoalRepository goals,GoalReflectionRepository reflections,AgentEventBus bus,Clock clock) {
    this.goals=goals;this.reflections=reflections;this.bus=bus;this.clock=clock;
  }
  @PostConstruct public synchronized void start(){if(subscription==null)subscription=bus.subscribe(this::observe);}
  @PreDestroy public synchronized void close(){if(subscription!=null){subscription.unsubscribe();subscription=null;}}
  private void observe(AgentEvent source) {
    if(source.type()!=AgentEventType.GOAL_UPDATED||source.projectId()==null||!(source.payload() instanceof GoalLifecyclePayload snapshot)||!STOPPED.contains(snapshot.status()))return;
    reflect(source,snapshot);
  }
  /** Human backfill after missed delivery/restart; this does not re-run or re-verify a Goal. */
  public GoalReflectionRepository.Item collect(String project,String id) {
    var goal=goals.get(project,id);
    if(!STOPPED.contains(goal.status()))throw new IllegalStateException("Only stopped or completed Goals can be reflected on");
    var snapshot=new GoalLifecyclePayload(goal.id(),goal.status(),goal.attempts(),goal.maxRuns(),goal.llmCallsUsed(),goal.maxLlmCalls(),goal.reason());
    return reflect(new AgentEvent("manual-collect:"+UUID.randomUUID(),0,clock.instant(),AgentEventType.GOAL_UPDATED,1,goal.sessionId(),null,
        goal.currentRunId(),goal.id(),null,snapshot,goal.projectId()),snapshot);
  }
  private GoalReflectionRepository.Item reflect(AgentEvent source,GoalLifecyclePayload snapshot) {
    var goal=goals.get(source.projectId(),snapshot.goalId());
    var attempt=goals.attempts(goal.projectId(),goal.id()).stream()
        .filter(a->Objects.equals(a.runId(),source.runId())&&a.number()==snapshot.attempts()).findFirst();
    String actual="NOT_CHECKED",reason="not_recorded";
    if(snapshot.status().equals("COMPLETED")&&Set.of("file_digest_verified","criteria_verified").contains(snapshot.reason())) {
      actual="VERIFIED";reason=snapshot.reason();
    } else if(attempt.isPresent()) {
      reason=attempt.get().reason();
      if(Set.of("digest_mismatch","json_value_mismatch","file_missing_or_not_regular").contains(reason))actual="UNVERIFIED";
      else if(Set.of("symbolic_link_rejected","outside_project","file_too_large","project_path_changed","json_file_too_large","json_file_invalid").contains(reason))actual="REJECTED";
    }
    String gap=switch(actual) {case "VERIFIED" -> "CRITERION_SATISFIED";case "UNVERIFIED" -> "CRITERION_NOT_SATISFIED";case "REJECTED" -> "VERIFICATION_REJECTED";default -> "VERIFICATION_NOT_RECORDED";};
    String next=switch(snapshot.status()) {
      case "COMPLETED" -> "REVIEW_VERIFIED_RESULT";
      case "WAITING_APPROVAL" -> "REVIEW_APPROVAL";
      case "FAILED" -> "INSPECT_EXECUTION_FAILURE";
      case "PAUSED","CANCELLED" -> "RECONCILE_STOPPED_RUN";
      default -> snapshot.reason().equals("budget_exhausted")||snapshot.attempts()>=snapshot.maxRuns()||snapshot.llmCallsUsed()>=snapshot.maxLlmCalls()
          ?"REVIEW_GOAL_BUDGET":actual.equals("REJECTED")?"INSPECT_VERIFICATION":"REVIEW_BLOCKER";
    };
    return reflections.save(goal,source,snapshot,actual,reason,gap,next);
  }
}
