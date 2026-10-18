package dev.mikoto2000.rei.goal;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Read-only independent verification, sharing the Goal file and evidence verification contracts.
 */
@Component
public final class GoalStageVerifier {
  public enum Result {
    VERIFIED,
    UNSATISFIED,
    UNKNOWN,
    REJECTED
  }

  public record Observation(
      Result result,
      String reason,
      Map<String, String> artifactVersions,
      Map<String, Long> dependencyVersions) {
    public Observation {
      artifactVersions = Map.copyOf(artifactVersions);
      dependencyVersions = Map.copyOf(dependencyVersions);
    }
  }

  private final FileGoalVerifier files;
  private GoalCompletionGate gate;

  public GoalStageVerifier(FileGoalVerifier files) {
    this.files = files;
  }

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void configureGate(GoalCompletionGate gate) {
    this.gate = gate;
  }

  public Observation verify(
      GoalRepository.Goal goal,
      GoalStageRepository.Stage stage,
      List<GoalStageRepository.Stage> stages) {
    var versions = new LinkedHashMap<String, String>();
    var dependencies = new LinkedHashMap<String, Long>();
    for (String id : stage.definition().dependsOnStageIds()) {
      var dependency = stages.stream().filter(s -> s.definition().id().equals(id)).findFirst();
      if (dependency.isEmpty() || !dependency.get().state().equals("VERIFIED"))
        return new Observation(
            Result.UNKNOWN, "stage_dependency_unverified", versions, dependencies);
      dependencies.put(id, dependency.get().version());
    }
    var root = Path.of(goal.projectRoot());
    FileGoalVerifier.Verification failure = null;
    for (var criterion : stage.definition().criteria()) {
      var observed = files.verify(root, criterion);
      var fingerprint = files.fingerprint(root, criterion.relativeFile());
      versions.put(
          criterion.relativeFile(),
          fingerprint.available() ? fingerprint.sha256() : fingerprint.reason());
      if (!observed.satisfied() && failure == null) failure = observed;
    }
    if (failure == null && stage.definition().completion() != null) {
      failure =
          gate == null
              ? new FileGoalVerifier.Verification(false, "completion_gate_unavailable")
              : gate.verify(
                  new GoalCompletionGate.Subject(
                      goal.projectId(),
                      goal.projectRoot(),
                      goal.sessionId(),
                      goal.currentRunId(),
                      stage.definition().completion(),
                      stage.proof()),
                  files);
      if (failure.satisfied()) failure = null;
    }
    if (failure == null)
      return new Observation(Result.VERIFIED, "criteria_verified", versions, dependencies);
    var diagnosis = GoalRepairDiagnosis.of(failure.reason());
    return new Observation(
        diagnosis.repairable()
            ? Result.UNSATISFIED
            : diagnosis.kind() == GoalRepairDiagnosis.Kind.UNREPAIRABLE
                ? Result.REJECTED
                : Result.UNKNOWN,
        failure.reason(),
        versions,
        dependencies);
  }
}
