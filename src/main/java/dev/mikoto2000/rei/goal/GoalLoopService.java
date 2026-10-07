package dev.mikoto2000.rei.goal;

import java.util.function.Consumer;
import org.springframework.stereotype.Service;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.core.policy.ToolPermissionProperties;

/** Observe -> act using the existing Chat planner -> independently verify -> bounded continuation. */
@Service
public class GoalLoopService {
  public record Outcome(ChatExecutionResult result,String stopReason) {
    public Outcome(ChatExecutionResult result){this(result,"");}
  }
  @FunctionalInterface public interface Gateway {
    void dispatch(GoalRepository.Claim claim,String run,Consumer<Outcome> completed);
    default void validate(GoalRepository.Goal goal) {}
    default void cancel(GoalRepository.Goal goal) {}
    default boolean isInFlight(GoalRepository.Goal goal) {return true;}
  }
  private final GoalRepository goals;
  private final FileGoalVerifier verifier;
  private final Gateway gateway;
  private final ToolPermissionProperties permissions;
  private final GoalEvents events;
  public GoalLoopService(GoalRepository goals,FileGoalVerifier verifier,Gateway gateway,ToolPermissionProperties permissions,GoalEvents events) {
    this.goals=goals;this.verifier=verifier;this.gateway=gateway;this.permissions=permissions;this.events=events;
  }
  public GoalRepository.Goal create(AgentRunContext owner,String objective,String file,String digest,int runs,int calls) {
    gateway.validate(new GoalRepository.Goal("pending",owner.projectId(),owner.projectRoot().toString(),owner.conversationId(),objective,file,digest,runs,calls,0,0,"READY",null,""));
    var goal=goals.create(owner,objective,file,digest,runs,calls);events.publish(goal);return goal;
  }
  public GoalRepository.Goal create(AgentRunContext owner,String objective,java.util.List<GoalRepository.FileCriterion> criteria,int runs,int calls) {
    if(criteria==null||criteria.isEmpty())throw new IllegalArgumentException("Goal requires file criteria");
    var first=criteria.getFirst();
    gateway.validate(new GoalRepository.Goal("pending",owner.projectId(),owner.projectRoot().toString(),owner.conversationId(),objective,first.relativeFile(),first.sha256(),runs,calls,0,0,"READY",null,"",criteria));
    var goal=goals.create(owner,objective,criteria,runs,calls);events.publish(goal);return goal;
  }
  /** Human-facing dispatch only, never a model Tool or automatic startup restoration. */
  public synchronized GoalRepository.Goal run(String project,String id) {
    if(!permissions.enabled())throw new IllegalStateException("Enable rei.tool-permission.enabled before running a Goal");
    var goal=goals.get(project,id);gateway.validate(goal);
    if(!goal.status().equals("RUNNING")&&!goal.status().equals("CANCELLED")&&verifier.verify(goal).satisfied())return verify(project,id).goal();
    var claim=goals.claim(project,id);next(claim);return goals.get(project,id);
  }
  public record Inspection(GoalRepository.Goal goal,FileGoalVerifier.Verification verification) {}
  public Inspection verify(String project,String id) {
    var goal=goals.get(project,id);gateway.validate(goal);var verification=verifier.verify(goal);
    if(verification.satisfied()&&!java.util.Set.of("COMPLETED","RUNNING","CANCELLED").contains(goal.status())) {
      goal=goals.verifiedWithoutRun(project,id);events.publish(goal);
    }
    return new Inspection(goal,verification);
  }
  public synchronized GoalRepository.Goal reconcile(String project,String id,String expectedRunId) {
    var goal=goals.get(project,id);gateway.validate(goal);
    if(gateway.isInFlight(goal))throw new IllegalStateException("Goal Run is queued or executing; stop it through the normal Run controls");
    var result=goals.reconcile(project,id,expectedRunId);events.publish(result);return result;
  }
  public GoalRepository.Goal cancel(String project,String id) {
    var before=goals.get(project,id);var goal=goals.cancel(project,id);
    if(before.status().equals("RUNNING"))gateway.cancel(goal);
    events.publish(goal);return goal;
  }
  public GoalRepository.Goal cancel(String project,String id,String expectedRun,long expectedAttempts) {
    var before=goals.get(project,id);var goal=goals.cancel(project,id,expectedRun,expectedAttempts);
    if(before.status().equals("RUNNING"))gateway.cancel(goal);
    events.publish(goal);return goal;
  }
  private synchronized void next(GoalRepository.Claim claim) {
    if(!goals.active(claim))return;
    var goal=goals.get(claim.goal().projectId(),claim.goal().id());
    if(goal.maxTotalTokens()>0&&goal.pendingLlmCalls()>0) {stop(claim,"BLOCKED","token_usage_unknown");return;}
    if(goals.tokenExhausted(claim)) {stop(claim,"BLOCKED",goal.tokenUsageUnknown()?"token_usage_unknown":"token_budget_exhausted");return;}
    if(goal.attempts()>=goal.maxRuns()||goal.llmCallsUsed()>=goal.maxLlmCalls()) {stop(claim,"BLOCKED","budget_exhausted");return;}
    String run=goals.beginAttempt(claim);events.publish(goals.get(goal.projectId(),goal.id()));
    try {gateway.dispatch(claim,run,outcome->completed(claim,run,outcome));}
    catch(RuntimeException error){if(goals.active(claim)){goals.recordAttempt(claim,run,"FAILED","admission_failed");stop(claim,"FAILED","admission_failed");}}
  }
  private void completed(GoalRepository.Claim claim,String run,Outcome outcome) {
    if(!goals.active(claim))return;
    var result=outcome.result();
    if(result.status()==ChatExecutionResult.Status.CANCELLED||Thread.currentThread().isInterrupted()) {
      goals.recordAttempt(claim,run,"CANCELLED","run_cancelled");stop(claim,"PAUSED","run_cancelled");return;
    }
    if(!result.success()) {
      if(goals.tokenExhausted(claim)) {
        var goal=goals.get(claim.goal().projectId(),claim.goal().id());
        String reason=goal.tokenUsageUnknown()?"token_usage_unknown":"token_budget_exhausted";
        goals.recordAttempt(claim,run,"BLOCKED",reason);stop(claim,"BLOCKED",reason);return;
      }
      String state=switch(outcome.stopReason()) {case "permission_required" -> "WAITING_APPROVAL";case "policy_denied" -> "BLOCKED";default -> goals.remainingLlm(claim)==0?"BLOCKED":"FAILED";};
      goals.recordAttempt(claim,run,state,"execution_stopped");stop(claim,state,"execution_stopped");return;
    }
    try {gateway.validate(claim.goal());}
    catch(RuntimeException error){goals.recordAttempt(claim,run,"BLOCKED","owner_unavailable");stop(claim,"BLOCKED","owner_unavailable");return;}
    var verification=verifier.verify(claim.goal());
    goals.recordAttempt(claim,run,verification.satisfied()?"VERIFIED":"UNVERIFIED",verification.reason());
    if(verification.satisfied()){stop(claim,"COMPLETED",verification.reason());return;}
    if(!java.util.Set.of("digest_mismatch","json_value_mismatch","file_missing_or_not_regular").contains(verification.reason())) {
      stop(claim,"BLOCKED",verification.reason());return;
    }
    next(claim);
  }
  private void stop(GoalRepository.Claim claim,String state,String reason) {
    if(goals.active(claim))events.publish(goals.stop(claim,state,reason));
  }
}
