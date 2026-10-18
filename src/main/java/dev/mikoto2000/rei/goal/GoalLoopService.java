package dev.mikoto2000.rei.goal;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.ChatExecutionResult;
import dev.mikoto2000.rei.core.policy.ToolPermissionProperties;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;

/**
 * Observe -> act using the existing Chat planner -> independently verify -> bounded continuation.
 */
@Service
public class GoalLoopService {
  public record Outcome(ChatExecutionResult result, String stopReason) {
    public Outcome(ChatExecutionResult result) {
      this(result, "");
    }
  }

  @FunctionalInterface
  public interface Gateway {
    void dispatch(GoalRepository.Claim claim, String run, Consumer<Outcome> completed);

    default void validate(GoalRepository.Goal goal) {}

    default void cancel(GoalRepository.Goal goal) {}

    default boolean isInFlight(GoalRepository.Goal goal) {
      return true;
    }
  }

  private final GoalRepository goals;
  private final FileGoalVerifier verifier;
  private final Gateway gateway;
  private final ToolPermissionProperties permissions;
  private final GoalEvents events;
  private GoalCompletionGate completionGate;
  private GoalWaitRepository waits;
  private GoalStageService stages;

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void configureStages(GoalStageService stages) {
    this.stages = stages;
  }

  public GoalStageService.Progress stageProgress(String project, String id) {
    var goal = goals.get(project, id);
    gateway.validate(goal);
    if (stages == null) throw new IllegalStateException("Stage management unavailable");
    return stages.progress(goal);
  }

  public java.util.List<GoalStageRepository.Stage> defineStages(
      String project, String id, long version, java.util.List<GoalStageDefinition> plan) {
    var goal = goals.get(project, id);
    gateway.validate(goal);
    if (stages == null) throw new IllegalStateException("Stages unavailable");
    if (waits != null && waits.active(project, id))
      throw new IllegalStateException("Cancel the active Goal wait before changing stages");
    var result = stages.define(goal, version, plan);
    events.publish(goals.get(project, id));
    return result;
  }

  public GoalStageService.Progress attachStageEvidence(
      String project, String id, String stage, long version, GoalCompletionGate.Proof proof) {
    var goal = goals.get(project, id);
    gateway.validate(goal);
    if (stages == null) throw new IllegalStateException("Stages unavailable");
    stages.attach(goal, stage, version, proof);
    events.publish(goal);
    return stages.progress(goal);
  }

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void configureWaitRepository(GoalWaitRepository waits) {
    this.waits = waits;
  }

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setCompletionGate(GoalCompletionGate gate) {
    completionGate = gate;
  }

  public GoalLoopService(
      GoalRepository goals,
      FileGoalVerifier verifier,
      Gateway gateway,
      ToolPermissionProperties permissions,
      GoalEvents events) {
    this.goals = goals;
    this.verifier = verifier;
    this.gateway = gateway;
    this.permissions = permissions;
    this.events = events;
  }

  public GoalRepository.Goal create(
      AgentRunContext owner, String objective, String file, String digest, int runs, int calls) {
    gateway.validate(
        new GoalRepository.Goal(
            "pending",
            owner.projectId(),
            owner.projectRoot().toString(),
            owner.conversationId(),
            objective,
            file,
            digest,
            runs,
            calls,
            0,
            0,
            "READY",
            null,
            ""));
    var goal = goals.create(owner, objective, file, digest, runs, calls);
    events.publish(goal);
    return goal;
  }

  public GoalRepository.Goal create(
      AgentRunContext owner,
      String objective,
      java.util.List<GoalRepository.FileCriterion> criteria,
      int runs,
      int calls) {
    if (criteria == null || criteria.isEmpty())
      throw new IllegalArgumentException("Goal requires file criteria");
    var first = criteria.getFirst();
    gateway.validate(
        new GoalRepository.Goal(
            "pending",
            owner.projectId(),
            owner.projectRoot().toString(),
            owner.conversationId(),
            objective,
            first.relativeFile(),
            first.sha256(),
            runs,
            calls,
            0,
            0,
            "READY",
            null,
            "",
            criteria));
    var goal = goals.create(owner, objective, criteria, runs, calls);
    events.publish(goal);
    return goal;
  }

  public GoalRepository.Goal create(
      AgentRunContext owner,
      String objective,
      java.util.List<GoalRepository.FileCriterion> criteria,
      GoalCompletionGate.Definition definition,
      int runs,
      int calls) {
    GoalCompletionGate.validateDefinition(definition);
    if (criteria == null || criteria.isEmpty())
      throw new IllegalArgumentException("File criteria required");
    var first = criteria.getFirst();
    gateway.validate(
        new GoalRepository.Goal(
            "pending",
            owner.projectId(),
            owner.projectRoot().toString(),
            owner.conversationId(),
            objective,
            first.relativeFile(),
            first.sha256(),
            runs,
            calls,
            0,
            0,
            "READY",
            null,
            "",
            criteria));
    var goal = goals.create(owner, objective, criteria, definition, runs, calls);
    events.publish(goal);
    return goal;
  }

  public GoalRepository.Goal defineCompletion(
      AgentRunContext owner, String id, GoalCompletionGate.Definition definition) {
    gateway.validate(goals.get(owner.projectId(), id));
    var goal = goals.defineCompletion(owner, id, definition);
    events.publish(goal);
    return goal;
  }

  public GoalRepository.Goal attachCompletion(
      AgentRunContext owner, String id, GoalCompletionGate.Proof proof) {
    if (completionGate == null) throw new IllegalStateException("Goal gate unavailable");
    gateway.validate(goals.get(owner.projectId(), id));
    try {
      var goal = completionGate.attach(owner, id, proof, true);
      events.publish(goal);
      return goal;
    } catch (java.io.IOException invalid) {
      throw new IllegalStateException("Goal proof unavailable", invalid);
    }
  }

  public GoalRepository.Goal defineCompletion(
      String project, String id, GoalCompletionGate.Definition definition) {
    return defineCompletion(humanCompletionOwner(project, id), id, definition);
  }

  public GoalRepository.Goal attachCompletion(
      String project, String id, GoalCompletionGate.Proof proof) {
    return attachCompletion(humanCompletionOwner(project, id), id, proof);
  }

  private AgentRunContext humanCompletionOwner(String project, String id) {
    var goal = goals.get(project, id);
    return new AgentRunContext(
        java.util.UUID.randomUUID().toString(),
        goal.sessionId(),
        java.nio.file.Path.of(goal.projectRoot()),
        goal.projectId());
  }

  /** Human-facing dispatch only, never a model Tool or automatic startup restoration. */
  public synchronized GoalRepository.Goal run(String project, String id) {
    if (!permissions.enabled())
      throw new IllegalStateException("Enable rei.tool-permission.enabled before running a Goal");
    var goal = goals.get(project, id);
    gateway.validate(goal);
    verifier.requireEnabledPredicates(goal);
    if (waits != null && waits.active(project, id))
      throw new IllegalStateException("Use the condition-checked Goal wait resume control");
    if (stages != null && !goal.status().equals("RUNNING")) stages.resumeExplicit(goal);
    if (!goal.status().equals("RUNNING")
        && !goal.status().equals("CANCELLED")
        && (stages == null || stages.allVerified(goal))
        && verifier.verify(goal).satisfied()) return verify(project, id).goal();
    var claim = goals.claim(project, id);
    next(claim);
    return goals.get(project, id);
  }

  synchronized GoalRepository.Goal resumeWaiting(
      GoalRepository.Goal expected,
      GoalWaitRepository.Wait wait,
      Consumer<AgentRunContext> preparation) {
    if (!permissions.enabled())
      throw new IllegalStateException("Enable tool permissions before Goal resume");
    gateway.validate(expected);
    verifier.requireEnabledPredicates(expected);
    if (stages != null) stages.resumeExplicit(expected);
    var claim = goals.claim(expected, wait);
    next(claim, preparation);
    var result = goals.get(expected.projectId(), expected.id());
    if (result.reason().equals("admission_failed"))
      throw new IllegalStateException("Goal continuation admission failed");
    return result;
  }

  public record Inspection(GoalRepository.Goal goal, FileGoalVerifier.Verification verification) {}

  public GoalCompletionProgress progress(String project, String id) {
    var goal = goals.get(project, id);
    gateway.validate(goal);
    var stageProgress = stages == null ? null : stages.progress(goal);
    goal = goals.get(project, id);
    var progress =
        GoalCompletionProgress.inspect(goal, goals.completionPhase(project, id), verifier);
    return stageProgress == null ? progress : progress.withStages(stageProgress);
  }

  public Inspection verify(String project, String id) {
    var goal = goals.get(project, id);
    gateway.validate(goal);
    var verification = verifier.verify(goal);
    if (stages != null && !stages.allVerified(goal))
      verification = new FileGoalVerifier.Verification(false, "stage_verification_required");
    if (verification.satisfied()
        && !java.util.Set.of("COMPLETED", "RUNNING", "CANCELLED").contains(goal.status())) {
      goal = goals.verifiedWithoutRun(goal, verification.reason());
      events.publish(goal);
    } else goal = goals.get(project, id);
    return new Inspection(goal, verification);
  }

  public synchronized GoalRepository.Goal reconcile(
      String project, String id, String expectedRunId) {
    var goal = goals.get(project, id);
    gateway.validate(goal);
    if (gateway.isInFlight(goal))
      throw new IllegalStateException(
          "Goal Run is queued or executing; stop it through the normal Run controls");
    var result =
        goals.atomic(
            () -> {
              var reconciled = goals.reconcile(project, id, expectedRunId);
              if (stages != null) stages.reconcile(reconciled, expectedRunId);
              return reconciled;
            });
    events.publish(result);
    return result;
  }

  public GoalRepository.Goal cancel(String project, String id) {
    var before = goals.get(project, id);
    var goal =
        goals.atomic(
            () -> {
              var cancelled = goals.cancel(project, id);
              if (stages != null) stages.cancelled(cancelled);
              return cancelled;
            });
    if (before.status().equals("RUNNING")) gateway.cancel(goal);
    events.publish(goal);
    return goal;
  }

  public GoalRepository.Goal cancel(
      String project, String id, String expectedRun, long expectedAttempts) {
    var before = goals.get(project, id);
    var goal =
        goals.atomic(
            () -> {
              var cancelled = goals.cancel(project, id, expectedRun, expectedAttempts);
              if (stages != null) stages.cancelled(cancelled);
              return cancelled;
            });
    if (before.status().equals("RUNNING")) gateway.cancel(goal);
    events.publish(goal);
    return goal;
  }

  private synchronized void next(GoalRepository.Claim claim) {
    next(claim, null);
  }

  private synchronized void next(
      GoalRepository.Claim claim, Consumer<AgentRunContext> preparation) {
    if (!goals.active(claim)) return;
    var goal = goals.get(claim.goal().projectId(), claim.goal().id());
    var stageManagement = stages;
    GoalStageService.Selection selection =
        stageManagement == null ? null : stageManagement.select(goal);
    if (selection != null && !selection.blocker().isBlank()) {
      stop(claim, "BLOCKED", selection.blocker());
      return;
    }
    if (selection != null && stages.enabled(goal) && selection.allVerified()) {
      var finalVerification = verifier.verify(goal);
      if (finalVerification.satisfied()) {
        stop(claim, "COMPLETED", finalVerification.reason());
        return;
      }
    }
    if (goal.maxTotalTokens() > 0 && goal.pendingLlmCalls() > 0) {
      stop(claim, "BLOCKED", "token_usage_unknown");
      return;
    }
    if (goals.tokenExhausted(claim)) {
      stop(
          claim,
          "BLOCKED",
          goal.tokenUsageUnknown() ? "token_usage_unknown" : "token_budget_exhausted");
      return;
    }
    if (goal.attempts() >= goal.maxRuns() || goal.llmCallsUsed() >= goal.maxLlmCalls()) {
      stop(claim, "BLOCKED", "budget_exhausted");
      return;
    }
    boolean repairing =
        goals.attempts(goal.projectId(), goal.id()).stream()
            .reduce((a, b) -> b)
            .map(a -> a.status().equals("UNVERIFIED"))
            .orElse(false);
    if (!goals.completionPhase(claim, repairing ? "REPAIRING" : "RUNNING")) return;
    String run =
        goals.atomic(
            () -> {
              String admitted = goals.beginAttempt(claim);
              if (stageManagement != null && selection != null && selection.stage() != null)
                stageManagement.begin(claim, admitted, selection.stage());
              return admitted;
            });
    events.publish(
        goals.get(goal.projectId(), goal.id()), goals.completionPhase(goal.projectId(), goal.id()));
    try {
      if (preparation != null)
        preparation.accept(
            new AgentRunContext(
                run,
                goal.sessionId(),
                java.nio.file.Path.of(goal.projectRoot()),
                goal.projectId(),
                AgentRunContext.RequestSource.WEB));
      gateway.dispatch(claim, run, outcome -> completed(claim, run, outcome));
    } catch (RuntimeException error) {
      if (goals.active(claim, run)) {
        recordAttempt(claim, run, "FAILED", "admission_failed");
        stop(claim, "FAILED", "admission_failed");
      }
    }
  }

  private synchronized void completed(GoalRepository.Claim claim, String run, Outcome outcome) {
    if (!goals.active(claim, run)) return;
    var result = outcome.result();
    if (result.status() == ChatExecutionResult.Status.CANCELLED
        || Thread.currentThread().isInterrupted()) {
      recordAttempt(claim, run, "CANCELLED", "run_cancelled");
      stop(claim, "PAUSED", "run_cancelled");
      return;
    }
    var current = goals.get(claim.goal().projectId(), claim.goal().id());
    if (current.maxTotalTokens() > 0
        && (current.pendingLlmCalls() > 0
            || current.tokenUsageUnknown()
            || current.totalTokens() > current.maxTotalTokens())) {
      String reason =
          current.pendingLlmCalls() > 0 || current.tokenUsageUnknown()
              ? "token_usage_unknown"
              : "token_budget_exhausted";
      recordAttempt(claim, run, "BLOCKED", reason);
      stop(claim, "BLOCKED", reason);
      return;
    }
    boolean controlledBudgetEnd =
        current.completion() != null
            && (outcome.stopReason().equals("repair_reserve_reached")
                || result.stopCode().equals("llm_call_budget_exceeded"));
    if (!result.success() && !controlledBudgetEnd) {
      if (goals.tokenExhausted(claim)) {
        var goal = goals.get(claim.goal().projectId(), claim.goal().id());
        String reason = goal.tokenUsageUnknown() ? "token_usage_unknown" : "token_budget_exhausted";
        recordAttempt(claim, run, "BLOCKED", reason);
        stop(claim, "BLOCKED", reason);
        return;
      }
      String state =
          switch (outcome.stopReason()) {
            case "permission_required" -> "WAITING_APPROVAL";
            case "policy_denied" -> "BLOCKED";
            default -> goals.remainingLlm(claim) == 0 ? "BLOCKED" : "FAILED";
          };
      String reason = outcome.stopReason().isBlank() ? "execution_stopped" : outcome.stopReason();
      recordAttempt(claim, run, state, reason);
      stop(claim, state, reason);
      return;
    }
    try {
      gateway.validate(claim.goal());
    } catch (RuntimeException error) {
      recordAttempt(claim, run, "BLOCKED", "owner_unavailable");
      stop(claim, "BLOCKED", "owner_unavailable");
      return;
    }
    if (!goals.completionPhase(claim, "VERIFYING")) return;
    events.publish(goals.get(claim.goal().projectId(), claim.goal().id()), "VERIFYING");
    var stageManagement = stages;
    if (stageManagement != null) {
      var stageVerification = stageManagement.verifyRun(current, run);
      if (stageVerification != null) {
        boolean recorded =
            goals.atomic(
                () -> {
                  if (!stageManagement.finish(claim, run, stageVerification)) return false;
                  goals.recordAttempt(
                      claim,
                      run,
                      stageVerification.result() == GoalStageVerifier.Result.VERIFIED
                          ? "VERIFIED"
                          : "UNVERIFIED",
                      stageVerification.reason());
                  return true;
                });
        if (!recorded) return;
        if (stageVerification.result() == GoalStageVerifier.Result.UNSATISFIED) {
          try {
            stages.applyProposal(claim, run);
          } catch (IllegalArgumentException | IllegalStateException invalid) {
            stop(claim, "BLOCKED", "stage_plan_rejected");
            return;
          }
        }
        if (stageVerification.result() != GoalStageVerifier.Result.VERIFIED
            && stageVerification.result() != GoalStageVerifier.Result.UNSATISFIED) {
          stop(claim, "BLOCKED", stageVerification.reason());
          return;
        }
        next(claim);
        return;
      }
    }
    var verifiedGoal = goals.get(claim.goal().projectId(), claim.goal().id());
    var verification = verifier.verify(verifiedGoal);
    // Final Goal repair Runs have no Stage Attempt, but must preserve every Stage contract too.
    if (stageManagement != null && !stageManagement.allVerified(verifiedGoal)) {
      recordAttempt(claim, run, "UNVERIFIED", "stage_evidence_stale");
      stop(claim, "BLOCKED", "stage_evidence_stale");
      return;
    }
    recordAttempt(
        claim, run, verification.satisfied() ? "VERIFIED" : "UNVERIFIED", verification.reason());
    if (verification.satisfied()) {
      stop(claim, "COMPLETED", verification.reason());
      return;
    }
    if (!GoalRepairDiagnosis.of(verification.reason()).repairable()) {
      stop(claim, "BLOCKED", verification.reason());
      return;
    }
    if (controlledBudgetEnd && !outcome.stopReason().equals("repair_reserve_reached")) {
      stop(claim, "BLOCKED", "budget_exhausted");
      return;
    }
    next(claim);
  }

  private void stop(GoalRepository.Claim claim, String state, String reason) {
    if (goals.active(claim)) events.publish(goals.stop(claim, state, reason));
  }

  private void recordAttempt(GoalRepository.Claim claim, String run, String state, String reason) {
    goals.atomic(
        () -> {
          if (stages != null) stages.interrupted(claim, run, reason);
          goals.recordAttempt(claim, run, state, reason);
          return null;
        });
  }
}
