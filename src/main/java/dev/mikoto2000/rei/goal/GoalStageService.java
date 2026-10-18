package dev.mikoto2000.rei.goal;

import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/** Host decisions inside the existing Goal loop; this class never dispatches a Run. */
@Service
public class GoalStageService {
  public record EventStage(
      String id, String state, String reason, long version, String evidenceReference) {}

  public record EventProgress(long planVersion, String currentStageId, List<EventStage> stages) {
    public EventProgress {
      stages = List.copyOf(stages);
    }
  }

  public EventProgress eventProgress(GoalRepository.Goal goal) {
    var current = stages.list(goal);
    return new EventProgress(
        stages.planVersion(goal),
        current.stream()
            .filter(s -> Set.of("RUNNING", "VERIFYING", "REPAIRING").contains(s.state()))
            .map(s -> s.definition().id())
            .findFirst()
            .orElse(null),
        current.stream()
            .map(
                s ->
                    new EventStage(
                        s.definition().id(),
                        s.state(),
                        s.reason(),
                        s.version(),
                        s.evidence() == null ? null : s.evidence().reference()))
            .toList());
  }

  public record Selection(GoalStageRepository.Stage stage, boolean allVerified, String blocker) {}

  public record Progress(
      long planVersion,
      List<GoalStageRepository.Stage> stages,
      List<GoalStageRepository.Attempt> attempts,
      List<GoalStageRepository.Evidence> evidence,
      List<String> planHistory,
      int remainingRuns,
      int remainingLlmCalls,
      Long remainingTokens,
      String stopReason,
      String resumeAction) {
    public Progress {
      stages = List.copyOf(stages);
      attempts = List.copyOf(attempts);
      evidence = List.copyOf(evidence);
      planHistory = List.copyOf(planHistory);
    }

    @com.fasterxml.jackson.annotation.JsonProperty
    public String currentStageId() {
      return stages.stream()
          .filter(s -> Set.of("RUNNING", "VERIFYING", "REPAIRING").contains(s.state()))
          .map(s -> s.definition().id())
          .findFirst()
          .orElse(null);
    }
  }

  private final GoalStageRepository stages;
  private final GoalStageVerifier verifier;
  private final GoalRepository goals;
  private int maxRepairs = 2;

  @org.springframework.beans.factory.annotation.Value("${rei.goal.stage.max-repairs:2}")
  public void configureRepair(int maxRepairs) {
    if (maxRepairs < 0 || maxRepairs > 5)
      throw new IllegalArgumentException("Stage repair bound 0..5 required");
    this.maxRepairs = maxRepairs;
  }

  public GoalStageService(
      GoalStageRepository stages, GoalStageVerifier verifier, GoalRepository goals) {
    this.stages = stages;
    this.verifier = verifier;
    this.goals = goals;
  }

  public boolean enabled(GoalRepository.Goal goal) {
    return stages.planVersion(goal) > 0;
  }

  public List<GoalStageRepository.Stage> refresh(GoalRepository.Goal goal) {
    return goals.atomic(
        () -> {
          var current = stages.list(goal);
          for (int pass = 0; pass <= current.size(); pass++) {
            boolean changed = false;
            for (var stage : current) {
              if (!stage.state().equals("VERIFIED")) continue;
              var observed = verifier.verify(goal, stage, current);
              var evidence = stage.evidence();
              if (observed.result() != GoalStageVerifier.Result.VERIFIED
                  || evidence == null
                  || evidence.planVersion() != stage.planVersion()
                  || !evidence
                      .conditionVersion()
                      .equals(GoalStageRepository.encode(stage.definition()))
                  || !evidence.artifactVersions().equals(observed.artifactVersions())
                  || !evidence.dependencyVersions().equals(observed.dependencyVersions())) {
                stages.state(
                    goal,
                    stage,
                    "STALE",
                    observed.result() == GoalStageVerifier.Result.VERIFIED
                        ? "stage_evidence_changed"
                        : observed.reason());
                changed = true;
              }
            }
            current = stages.list(goal);
            if (!changed) break;
          }
          return current;
        });
  }

  public Selection select(GoalRepository.Goal goal) {
    var current = refresh(goal);
    if (current.stream().allMatch(s -> s.state().equals("VERIFIED")))
      return new Selection(null, true, "");
    for (var candidate : current) {
      var stage = candidate;
      String selectedId = stageId(candidate);
      if (!Set.of("TODO", "STALE", "REPAIRING", "DECOMPOSED").contains(stage.state())) continue;
      boolean dependencies =
          stage.definition().dependsOnStageIds().stream()
              .allMatch(
                  id ->
                      current.stream()
                          .anyMatch(
                              s -> s.definition().id().equals(id) && s.state().equals("VERIFIED")));
      boolean children =
          current.stream()
              .filter(s -> selectedId.equals(s.definition().parentStageId()))
              .allMatch(s -> s.state().equals("VERIFIED"));
      if (dependencies && children) {
        if (stage.state().equals("REPAIRING")) {
          String blocker = repairBlocker(goal, stage);
          if (!blocker.isEmpty()) {
            stages.state(goal, stage, "BLOCKED", stage.reason());
            return new Selection(null, false, blocker);
          }
        }
        if (stage.state().equals("DECOMPOSED")) {
          stages.state(goal, stage, "TODO", "parent_requires_verification");
          stage =
              stages.list(goal).stream()
                  .filter(s -> s.definition().id().equals(selectedId))
                  .findFirst()
                  .orElseThrow();
        }
        return new Selection(stage, false, "");
      }
    }
    return new Selection(null, false, "stage_requires_intervention");
  }

  private static String stageId(GoalStageRepository.Stage stage) {
    return stage.definition().id();
  }

  private String repairBlocker(GoalRepository.Goal goal, GoalStageRepository.Stage stage) {
    if (!GoalRepairDiagnosis.of(stage.reason()).repairable()) return "stage_repair_not_allowed";
    var failures =
        stages.attempts(goal).stream()
            .filter(
                a ->
                    a.stageId().equals(stageId(stage))
                        && a.planVersion() == stage.planVersion()
                        && a.status().equals("UNSATISFIED"))
            .toList();
    if (failures.size() > maxRepairs) return "stage_repair_limit";
    if (failures.size() >= 2) {
      var previous =
          GoalStageRepository.decode(
              failures.get(failures.size() - 2).observation(), GoalStageVerifier.Observation.class);
      var latest =
          GoalStageRepository.decode(
              failures.getLast().observation(), GoalStageVerifier.Observation.class);
      if (previous.equals(latest)) return "stage_repair_stalled";
    }
    return "";
  }

  public GoalStageRepository.Attempt begin(
      GoalRepository.Claim claim, String run, GoalStageRepository.Stage stage) {
    return stages.begin(claim, run, stage);
  }

  public GoalStageVerifier.Observation verifyRun(GoalRepository.Goal goal, String run) {
    var attempt = stages.forRun(goal, run);
    if (attempt == null) return null;
    var stage =
        stages.list(goal).stream()
            .filter(s -> s.definition().id().equals(attempt.stageId()))
            .findFirst()
            .orElseThrow();
    if (stage.state().equals("RUNNING"))
      stages.state(goal, stage, "VERIFYING", "independent_verification");
    return observeRun(goal, run);
  }

  public GoalStageVerifier.Observation observeRun(GoalRepository.Goal goal, String run) {
    var attempt = stages.forRun(goal, run);
    if (attempt == null) return null;
    var current = refresh(goal);
    var stage =
        current.stream()
            .filter(s -> s.definition().id().equals(attempt.stageId()))
            .findFirst()
            .orElseThrow();
    return verifier.verify(goal, stage, current);
  }

  public boolean finish(
      GoalRepository.Claim claim, String run, GoalStageVerifier.Observation observation) {
    var attempt = stages.forRun(claim.goal(), run);
    return attempt != null && stages.finish(claim, attempt, observation);
  }

  public void interrupted(GoalRepository.Claim claim, String run, String reason) {
    if (stages.forRun(claim.goal(), run) != null)
      finish(
          claim,
          run,
          new GoalStageVerifier.Observation(
              GoalStageVerifier.Result.UNKNOWN, reason, java.util.Map.of(), java.util.Map.of()));
  }

  public void reconcile(GoalRepository.Goal goal, String run) {
    stages.reconcile(goal, run);
  }

  public void resumeExplicit(GoalRepository.Goal goal) {
    for (var stage : stages.list(goal))
      if (stage.state().equals("BLOCKED")
          && Set.of("permission_required", "run_cancelled").contains(stage.reason()))
        stages.state(goal, stage, "STALE", stage.reason());
  }

  public boolean allVerified(GoalRepository.Goal goal) {
    return refresh(goal).stream().allMatch(s -> s.state().equals("VERIFIED"));
  }

  public String prompt(GoalRepository.Goal goal, String run) {
    var attempt = stages.forRun(goal, run);
    if (attempt == null) return "";
    var current = stages.list(goal);
    var active =
        current.stream()
            .filter(s -> s.definition().id().equals(attempt.stageId()))
            .findFirst()
            .orElseThrow();
    return "\nCurrent Goal Stage (host-owned): "
        + active.definition()
        + "\nStage attempt ID: "
        + attempt.id()
        + "; plan version: "
        + attempt.planVersion()
        + "\nPrevious independently verified stages: "
        + current.stream().filter(s -> s.state().equals("VERIFIED")).toList()
        + "\nUnmet stage conditions / last diagnosis: "
        + active.reason()
        + "\nRemaining Goal budgets: Runs="
        + (goal.maxRuns() - goal.attempts())
        + ", LLM calls="
        + (goal.maxLlmCalls() - goal.llmCallsUsed())
        + ", tokens="
        + (goal.maxTotalTokens() == 0
            ? "unlimited by configuration"
            : goal.maxTotalTokens() - goal.totalTokens())
        + "\nWork only on this Stage through the existing Chat planner. Preserve previous outputs and all Goal requirements. "
        + "DONE is a claim; only the host may mark VERIFIED. Never repeat an unknown side effect. For stage review/test/artifact proof use attachGoalStageEvidence. If prior independent failure cannot be repaired, proposeGoalStageSplit with child contracts and current plan version. The host validates the proposal and independently rechecks the parent.";
  }

  public Progress progress(GoalRepository.Goal goal) {
    var current = refresh(goal);
    return new Progress(
        stages.planVersion(goal),
        current,
        stages.attempts(goal),
        stages.evidence(goal),
        stages.planHistory(goal),
        Math.max(0, goal.maxRuns() - goal.attempts()),
        Math.max(0, goal.maxLlmCalls() - goal.llmCallsUsed()),
        goal.maxTotalTokens() == 0 ? null : Math.max(0, goal.maxTotalTokens() - goal.totalTokens()),
        goal.reason(),
        goal.status().equals("RUNNING")
            ? "Inspect current Run; reconcile its exact ID after a process exit"
            : "Inspect effects and permissions; explicitly run or use condition-checked wait resume");
  }

  public List<GoalStageRepository.Stage> define(
      GoalRepository.Goal goal, long version, List<GoalStageDefinition> plan) {
    return stages.define(goal, version, plan);
  }

  public void attach(
      GoalRepository.Goal goal, String stage, long version, GoalCompletionGate.Proof proof) {
    stages.attachProof(goal, stage, version, proof);
  }

  private GoalRepository.Goal owningRun(
      dev.mikoto2000.rei.core.chat.AgentRunContext owner, String id) {
    var goal = goals.get(owner.projectId(), id);
    if (owner.mode() != dev.mikoto2000.rei.core.chat.AgentRunContext.Mode.EXCLUSIVE
        || !owner.conversationId().equals(goal.sessionId())
        || !owner.projectRoot().toString().equals(goal.projectRoot())
        || !goal.status().equals("RUNNING")
        || !owner.runId().equals(goal.currentRunId()))
      throw new IllegalStateException("Captured owning Goal Run required");
    return goal;
  }

  public void proposeSplit(
      dev.mikoto2000.rei.core.chat.AgentRunContext owner,
      String id,
      long version,
      List<GoalStageDefinition> children) {
    goals.atomic(
        () -> {
          var goal = owningRun(owner, id);
          var attempt = stages.forRun(goal, owner.runId());
          if (attempt == null
              || attempt.planVersion() != version
              || stages.attempts(goal).stream()
                  .noneMatch(
                      a ->
                          a.stageId().equals(attempt.stageId())
                              && a.status().equals("UNSATISFIED")))
            throw new IllegalStateException("Current failed Stage and plan version required");
          stages.propose(goal, attempt, children);
          return null;
        });
  }

  public void attachRunning(
      dev.mikoto2000.rei.core.chat.AgentRunContext owner,
      String id,
      GoalCompletionGate.Proof proof) {
    goals.atomic(
        () -> {
          var goal = owningRun(owner, id);
          var attempt = stages.forRun(goal, owner.runId());
          if (attempt == null) throw new IllegalStateException("Current Stage required");
          stages.attachRunningProof(goal, attempt, proof);
          return null;
        });
  }

  public boolean applyProposal(GoalRepository.Claim claim, String run) {
    return stages.applyProposal(claim, run);
  }
}
