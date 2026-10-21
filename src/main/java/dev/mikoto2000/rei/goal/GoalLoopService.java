package dev.mikoto2000.rei.goal;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.ChatExecutionResult;
import dev.mikoto2000.rei.core.policy.ToolPermissionProperties;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;

/**
 * 目標繰り返しのユースケースを実現する。
 *
 * <p>Observe -> act using the existing Chat planner -> independently verify -> bounded
 * continuation.
 */
@Service
public class GoalLoopService {
  /**
   * 目標の達成管理で受け渡す最終結果を表す。
   *
   * @param result 結果
   * @param stopReason 停止理由
   */
  public record Outcome(ChatExecutionResult result, String stopReason) {
    /** 目標の達成管理を利用するため、指定を補って共通の初期化へ引き渡す。 */
    public Outcome(ChatExecutionResult result) {
      this(result, "");
    }
  }

  /** 目標の達成管理に必要な受付窓口を扱う。 */
  @FunctionalInterface
  public interface Gateway {
    /** 目標の達成管理に関する実行先への引き渡しを処理する。 */
    void dispatch(GoalRepository.Claim claim, String run, Consumer<Outcome> completed);

    /** 利用条件に合わない指定を受け付けないよう、入力内容を検証する。 */
    default void validate(GoalRepository.Goal goal) {}

    /** 対象の実行をキャンセルする。 */
    default void cancel(GoalRepository.Goal goal) {}

    /** 目標の達成管理において、入力先Flightかどうかを判定する。 */
    default boolean isInFlight(GoalRepository.Goal goal) {
      return true;
    }
  }

  /** 目標一覧。 */
  private final GoalRepository goals;

  /** 確認の窓口。 */
  private final FileGoalVerifier verifier;

  /** 受付窓口。 */
  private final Gateway gateway;

  /** 権限一覧。 */
  private final ToolPermissionProperties permissions;

  /** イベント一覧。 */
  private final GoalEvents events;

  /** 完了を判定する窓口。 */
  private GoalCompletionGate completionGate;

  /** 待機一覧。 */
  private GoalWaitRepository waits;

  /** 段階一覧。 */
  private GoalStageService stages;

  /** 目標の達成管理に関する設定段階一覧を処理する。 */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void configureStages(GoalStageService stages) {
    this.stages = stages;
  }

  /** 目標の達成管理で用いる段階進捗を返す。 */
  public GoalStageService.Progress stageProgress(String project, String id) {
    var goal = goals.get(project, id);
    gateway.validate(goal);
    if (stages == null) throw new IllegalStateException("Stage management unavailable");
    return stages.progress(goal);
  }

  /** 目標の達成管理で用いる定義段階一覧を返す。 */
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

  /** 目標の達成管理で用いる関連付け段階根拠を返す。 */
  public GoalStageService.Progress attachStageEvidence(
      String project, String id, String stage, long version, GoalCompletionGate.Proof proof) {
    var goal = goals.get(project, id);
    gateway.validate(goal);
    if (stages == null) throw new IllegalStateException("Stages unavailable");
    stages.attach(goal, stage, version, proof);
    events.publish(goal);
    return stages.progress(goal);
  }

  /** 目標の達成管理に関する設定待機保存と検索の窓口を処理する。 */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void configureWaitRepository(GoalWaitRepository waits) {
    this.waits = waits;
  }

  /** 目標の達成管理で使用する完了を判定する窓口を設定する。 */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setCompletionGate(GoalCompletionGate gate) {
    completionGate = gate;
  }

  /** 指定された値や利用先を使って、GoalLoopServiceを初期化する。 */
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

  /** 指定された内容から新しい対象を作成する。 */
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

  /** 指定された内容から新しい対象を作成する。 */
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

  /** 指定された内容から新しい対象を作成する。 */
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

  /** 目標の達成管理で用いる定義完了条件を返す。 */
  public GoalRepository.Goal defineCompletion(
      AgentRunContext owner, String id, GoalCompletionGate.Definition definition) {
    gateway.validate(goals.get(owner.projectId(), id));
    var goal = goals.defineCompletion(owner, id, definition);
    events.publish(goal);
    return goal;
  }

  /** 目標の達成管理で用いる関連付け完了条件を返す。 */
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

  /** 目標の達成管理で用いる定義完了条件を返す。 */
  public GoalRepository.Goal defineCompletion(
      String project, String id, GoalCompletionGate.Definition definition) {
    return defineCompletion(humanCompletionOwner(project, id), id, definition);
  }

  /** 目標の達成管理で用いる関連付け完了条件を返す。 */
  public GoalRepository.Goal attachCompletion(
      String project, String id, GoalCompletionGate.Proof proof) {
    return attachCompletion(humanCompletionOwner(project, id), id, proof);
  }

  /** 目標の達成管理で用いる利用者完了条件所有者を返す。 */
  private AgentRunContext humanCompletionOwner(String project, String id) {
    var goal = goals.get(project, id);
    return new AgentRunContext(
        java.util.UUID.randomUUID().toString(),
        goal.sessionId(),
        java.nio.file.Path.of(goal.projectRoot()),
        goal.projectId());
  }

  /**
   * 目標の達成管理の操作を実行する。
   *
   * <p>Human-facing dispatch only, never a model Tool or automatic startup restoration.
   */
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

  /** 中断した作業を引き継ぐため、待機中を再開する。 */
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

  /**
   * 目標の達成管理で受け渡すInspectionを表す。
   *
   * @param goal 目標
   * @param verification 確認結果
   */
  public record Inspection(GoalRepository.Goal goal, FileGoalVerifier.Verification verification) {}

  /** 目標の達成管理で用いる進捗を返す。 */
  public GoalCompletionProgress progress(String project, String id) {
    var goal = goals.get(project, id);
    gateway.validate(goal);
    var stageProgress = stages == null ? null : stages.progress(goal);
    goal = goals.get(project, id);
    var progress =
        GoalCompletionProgress.inspect(goal, goals.completionPhase(project, id), verifier);
    return stageProgress == null ? progress : progress.withStages(stageProgress);
  }

  /** 要求された条件を満たすことを確認する。 */
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

  /** 現在の状態と記録を照合し、差異に応じて管理状態を整える。 */
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

  /** 対象の実行をキャンセルする。 */
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

  /** 対象の実行をキャンセルする。 */
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

  /** 目標の達成管理に関する次を処理する。 */
  private synchronized void next(GoalRepository.Claim claim) {
    next(claim, null);
  }

  /** 目標の達成管理に関する次を処理する。 */
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

  /** 目標の達成管理に関する完了を処理する。 */
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

  /** 実行中の処理を停止する。 */
  private void stop(GoalRepository.Claim claim, String state, String reason) {
    if (goals.active(claim)) events.publish(goals.stop(claim, state, reason));
  }

  /** 後から経緯や使用量を確認できるよう、試行を記録する。 */
  private void recordAttempt(GoalRepository.Claim claim, String run, String state, String reason) {
    goals.atomic(
        () -> {
          if (stages != null) stages.interrupted(claim, run, reason);
          goals.recordAttempt(claim, run, state, reason);
          return null;
        });
  }
}
