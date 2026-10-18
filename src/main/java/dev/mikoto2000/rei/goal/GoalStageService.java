package dev.mikoto2000.rei.goal;

import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/** Host decisions inside the existing Goal loop; this class never dispatches a Run. */
@Service
public class GoalStageService {
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
      String resumeAction) {}

  private final GoalStageRepository stages;
  private final GoalStageVerifier verifier;
  private final GoalRepository goals;

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

  public GoalStageRepository.Attempt begin(
      GoalRepository.Claim claim, String run, GoalStageRepository.Stage stage) {
    return stages.begin(claim, run, stage);
  }

  public GoalStageVerifier.Observation verifyRun(GoalRepository.Goal goal, String run) {
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
        + "DONE is a claim; only the host may mark VERIFIED. Never repeat an unknown side effect.";
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
}
