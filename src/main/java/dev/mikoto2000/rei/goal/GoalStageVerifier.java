package dev.mikoto2000.rei.goal;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 目標の達成管理に必要な目標段階確認の窓口を扱う。
 *
 * <p>Read-only independent verification, sharing the Goal file and evidence verification contracts.
 */
@Component
public final class GoalStageVerifier {
  /** 目標の達成管理における結果の選択肢。 */
  public enum Result {
    /** 確認済み。 */
    VERIFIED,
    /** 条件を満たしていない結果。 */
    UNSATISFIED,
    /** 不明。 */
    UNKNOWN,
    /** 拒否済み。 */
    REJECTED
  }

  /**
   * 目標の達成管理で受け渡す観測内容を表す。
   *
   * @param result 結果
   * @param reason 理由
   * @param artifactVersions 成果物Versions
   * @param dependencyVersions 依存条件Versions
   * @param satisfiedConditions 条件を満たしたかどうか条件一覧
   * @param totalConditions 合計条件一覧
   */
  public record Observation(
      Result result,
      String reason,
      Map<String, String> artifactVersions,
      Map<String, Long> dependencyVersions,
      int satisfiedConditions,
      int totalConditions) {
    /** 目標の達成管理を利用するため、指定を補って共通の初期化へ引き渡す。 */
    public Observation(
        Result result,
        String reason,
        Map<String, String> artifacts,
        Map<String, Long> dependencies) {
      this(result, reason, artifacts, dependencies, 0, 0);
    }

    /** 受け取った情報からObservationを作成する。 */
    public Observation {
      artifactVersions = Map.copyOf(artifactVersions);
      dependencyVersions = Map.copyOf(dependencyVersions);
    }
  }

  /** ファイル一覧。 */
  private final GoalCompletionGate.FileChecks files;

  /** 内容の同一性を判断する情報。 */
  private final java.util.function.BiFunction<Path, String, FileGoalVerifier.Fingerprint>
      fingerprints;

  /** 実行可否の判断。 */
  private GoalCompletionGate gate;

  /** 指定された値や利用先を使って、GoalStageVerifierを初期化する。 */
  public GoalStageVerifier(FileGoalVerifier files) {
    this.files = files::verify;
    this.fingerprints = files::fingerprint;
  }

  /** 目標の達成管理に関する設定実行可否の判断を処理する。 */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void configureGate(GoalCompletionGate gate) {
    this.gate = gate;
  }

  /** 要求された条件を満たすことを確認する。 */
  public Observation verify(
      GoalRepository.Goal goal,
      GoalStageRepository.Stage stage,
      List<GoalStageRepository.Stage> stages) {
    var versions = new LinkedHashMap<String, String>();
    var dependencies = new LinkedHashMap<String, Long>();
    var prerequisiteIds = new java.util.LinkedHashSet<>(stage.definition().dependsOnStageIds());
    stages.stream()
        .filter(s -> stage.definition().id().equals(s.definition().parentStageId()))
        .forEach(s -> prerequisiteIds.add(s.definition().id()));
    for (String id : prerequisiteIds) {
      var dependency = stages.stream().filter(s -> s.definition().id().equals(id)).findFirst();
      if (dependency.isEmpty() || !dependency.get().state().equals("VERIFIED"))
        return new Observation(
            Result.UNKNOWN, "stage_dependency_unverified", versions, dependencies);
      dependencies.put(id, dependency.get().version());
    }
    var root = Path.of(goal.projectRoot());
    FileGoalVerifier.Verification failure = null;
    int satisfied = 0;
    int total =
        stage.definition().criteria().size() + (stage.definition().completion() == null ? 0 : 1);
    for (var criterion : stage.definition().criteria()) {
      var observed = files.verify(root, criterion);
      if (observed.satisfied()) satisfied++;
      var fingerprint = fingerprints.apply(root, criterion.relativeFile());
      versions.put(
          criterion.relativeFile(),
          fingerprint.available() ? fingerprint.sha256() : fingerprint.reason());
      if (!observed.satisfied() && failure == null) failure = observed;
    }
    if (failure == null && stage.definition().completion() != null) {
      var completion = stage.definition().completion();
      var evidenceFiles =
          new java.util.ArrayList<GoalRepository.FileCriterion>(completion.completionEvidence());
      evidenceFiles.addAll(completion.requiredPredicates());
      completion.requirements().stream()
          .filter(GoalCompletionGate.Requirement::required)
          .forEach(r -> evidenceFiles.add(r.criterion()));
      for (var item : evidenceFiles) {
        var fingerprint = fingerprints.apply(root, item.relativeFile());
        versions.put(
            item.relativeFile(),
            fingerprint.available() ? fingerprint.sha256() : fingerprint.reason());
      }
      if (stage.proof() != null) {
        if (stage.proof().review() != null)
          versions.put("review:" + stage.proof().review().id(), stage.proof().review().sha256());
        stage
            .proof()
            .artifacts()
            .forEach(ref -> versions.put("artifact:" + ref.id(), ref.sha256()));
      }
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
      if (failure.satisfied()) {
        failure = null;
        satisfied++;
      }
    }
    if (failure == null)
      return new Observation(
          Result.VERIFIED, "criteria_verified", versions, dependencies, satisfied, total);
    var diagnosis = GoalRepairDiagnosis.of(failure.reason());
    return new Observation(
        diagnosis.repairable()
            ? Result.UNSATISFIED
            : diagnosis.kind() == GoalRepairDiagnosis.Kind.UNREPAIRABLE
                ? Result.REJECTED
                : Result.UNKNOWN,
        failure.reason(),
        versions,
        dependencies,
        satisfied,
        total);
  }
}
