package dev.mikoto2000.rei.goal;

import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 目標段階のユースケースを実現する。
 *
 * <p>Host decisions inside the existing Goal loop; this class never dispatches a Run.
 */
@Service
public class GoalStageService {
  /**
   * 目標の達成管理で受け渡すイベント段階を表す。
   *
   * @param id 識別子
   * @param state 状態
   * @param reason 理由
   * @param version 形式のバージョン
   * @param evidenceReference 根拠参照先
   */
  public record EventStage(
      String id, String state, String reason, long version, String evidenceReference) {}

  /**
   * 目標の達成管理で受け渡すイベント進捗を表す。
   *
   * @param planVersion 計画形式のバージョン
   * @param currentStageId 現在段階識別子
   * @param stages 段階一覧
   */
  public record EventProgress(long planVersion, String currentStageId, List<EventStage> stages) {
    /** 受け取った情報からEventProgressを作成する。 */
    public EventProgress {
      stages = List.copyOf(stages);
    }
  }

  /** 目標の達成管理で用いるイベント進捗を返す。 */
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

  /**
   * 目標の達成管理で受け渡す選択内容を表す。
   *
   * @param stage 段階
   * @param allVerified 全対象確認済み
   * @param blocker 作業を妨げる課題
   */
  public record Selection(GoalStageRepository.Stage stage, boolean allVerified, String blocker) {}

  /**
   * 目標の達成管理で受け渡す進捗を表す。
   *
   * @param planVersion 計画形式のバージョン
   * @param stages 段階一覧
   * @param attempts 試行回数
   * @param evidence 根拠
   * @param planHistory 計画履歴
   * @param remainingRuns 残量実行一覧
   * @param remainingLlmCalls 残量言語モデル呼び出し回数
   * @param remainingTokens 残量トークン数
   * @param stopReason 停止理由
   * @param resumeAction 再開操作
   */
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
    /** 受け取った情報からProgressを作成する。 */
    public Progress {
      stages = List.copyOf(stages);
      attempts = List.copyOf(attempts);
      evidence = List.copyOf(evidence);
      planHistory = List.copyOf(planHistory);
    }

    /** 目標の達成管理で用いる現在段階識別子を返す。 */
    @com.fasterxml.jackson.annotation.JsonProperty(
        access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
    public String currentStageId() {
      return stages.stream()
          .filter(s -> Set.of("RUNNING", "VERIFYING", "REPAIRING").contains(s.state()))
          .map(s -> s.definition().id())
          .findFirst()
          .orElse(null);
    }
  }

  /** 段階一覧。 */
  private final GoalStageRepository stages;

  /** 確認の窓口。 */
  private final GoalStageVerifier verifier;

  /** 目標一覧。 */
  private final GoalRepository goals;

  /** 最大修復一覧。 */
  private int maxRepairs = 2;

  /** 許可分割。 */
  private boolean allowSplit = true;

  /** 目標の達成管理に関する設定分割を処理する。 */
  @org.springframework.beans.factory.annotation.Value("${rei.goal.stage.allow-split:true}")
  public void configureSplit(boolean enabled) {
    allowSplit = enabled;
  }

  /** 目標の達成管理に関する設定修復を処理する。 */
  @org.springframework.beans.factory.annotation.Value("${rei.goal.stage.max-repairs:2}")
  public void configureRepair(int maxRepairs) {
    if (maxRepairs < 0 || maxRepairs > 5)
      throw new IllegalArgumentException("Stage repair bound 0..5 required");
    this.maxRepairs = maxRepairs;
  }

  /** 指定された値や利用先を使って、GoalStageServiceを初期化する。 */
  public GoalStageService(
      GoalStageRepository stages, GoalStageVerifier verifier, GoalRepository goals) {
    this.stages = stages;
    this.verifier = verifier;
    this.goals = goals;
  }

  /** この機能を利用する設定が有効かどうかを返す。 */
  public boolean enabled(GoalRepository.Goal goal) {
    return stages.planVersion(goal) > 0;
  }

  /** 目標の達成管理で用いる再確認を返す。 */
  public List<GoalStageRepository.Stage> refresh(GoalRepository.Goal goal) {
    var current = stages.list(goal);
    for (int pass = 0; pass <= current.size(); pass++) {
      boolean changed = false;
      var observedStages = current;
      for (var stage : current) {
        if (stage.state().equals("BLOCKED")
            && stage.reason().equals("stage_dependency_unverified")
            && stage.definition().dependsOnStageIds().stream()
                .allMatch(
                    id ->
                        observedStages.stream()
                            .anyMatch(
                                s ->
                                    s.definition().id().equals(id)
                                        && s.state().equals("VERIFIED")))) {
          stages.compareState(goal, stage, "STALE", "stage_dependency_restored");
          changed = true;
          continue;
        }
        if (!stage.state().equals("VERIFIED")) continue;
        var observed = verifier.verify(goal, stage, current);
        var evidence = stage.evidence();
        if (observed.result() != GoalStageVerifier.Result.VERIFIED
            || evidence == null
            || evidence.planVersion() != stage.planVersion()
            || !evidence
                .conditionVersion()
                .equals(GoalStageRepository.conditionVersion(stage.definition()))
            || !evidence.artifactVersions().equals(observed.artifactVersions())
            || !evidence.dependencyVersions().equals(observed.dependencyVersions())) {
          stages.compareState(
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
    if (!current.isEmpty() && current.stream().anyMatch(s -> !s.state().equals("VERIFIED")))
      goals.staleStageCompletion(goal);
    return current;
  }

  /** 指定された条件に応じて、利用する対象を選ぶ。 */
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

  /** 目標の達成管理で用いる段階識別子を返す。 */
  private static String stageId(GoalStageRepository.Stage stage) {
    return stage.definition().id();
  }

  /** 目標の達成管理で用いる修復作業を妨げる課題を返す。 */
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
    // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
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

  /** 目標の達成管理で用いる開始を返す。 */
  public GoalStageRepository.Attempt begin(
      GoalRepository.Claim claim, String run, GoalStageRepository.Stage stage) {
    return stages.begin(claim, run, stage);
  }

  /** 要求された条件を満たすか判断するため、実行を確認する。 */
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

  /** 状況を判断する根拠を得るため、実行を観測する。 */
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

  /** 終了時に必要な状態を反映し、処理を完了させる。 */
  public boolean finish(
      GoalRepository.Claim claim, String run, GoalStageVerifier.Observation observation) {
    var attempt = stages.forRun(claim.goal(), run);
    return attempt != null && stages.finish(claim, attempt, observation);
  }

  /** 目標の達成管理に関する中断されたを処理する。 */
  public void interrupted(GoalRepository.Claim claim, String run, String reason) {
    if (stages.forRun(claim.goal(), run) != null)
      finish(
          claim,
          run,
          new GoalStageVerifier.Observation(
              GoalStageVerifier.Result.UNKNOWN, reason, java.util.Map.of(), java.util.Map.of()));
  }

  /** 現在の状態と記録を照合し、差異に応じて管理状態を整える。 */
  public void reconcile(GoalRepository.Goal goal, String run) {
    stages.reconcile(goal, run);
  }

  /** 目標の達成管理に関するキャンセル済みを処理する。 */
  public void cancelled(GoalRepository.Goal goal) {
    stages.cancelled(goal);
  }

  /** 中断した作業を引き継ぐため、明示的なを再開する。 */
  public void resumeExplicit(GoalRepository.Goal goal) {
    for (var stage : stages.list(goal))
      if (stage.state().equals("BLOCKED")
          && Set.of("permission_required", "run_cancelled").contains(stage.reason()))
        stages.state(goal, stage, "STALE", stage.reason());
  }

  /** 目標の達成管理において、全対象確認済みに該当するか判定する。 */
  public boolean allVerified(GoalRepository.Goal goal) {
    return refresh(goal).stream().allMatch(s -> s.state().equals("VERIFIED"));
  }

  /** 目標の達成管理で用いるプロンプトを返す。 */
  public String prompt(GoalRepository.Goal goal, String run) {
    var attempt = stages.forRun(goal, run);
    if (attempt == null)
      return enabled(goal)
          ? "\nFinal Goal repair: preserve all independently verified Stage contracts and artifacts: "
              + stages.list(goal)
              + "\nThe host revalidates every Stage after this Run."
          : "";
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

  /** 目標の達成管理で用いる進捗を返す。 */
  public Progress progress(GoalRepository.Goal goal) {
    var current = refresh(goal);
    goal = goals.get(goal.projectId(), goal.id());
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

  /** 目標の達成管理で用いる定義を返す。 */
  public List<GoalStageRepository.Stage> define(
      GoalRepository.Goal goal, long version, List<GoalStageDefinition> plan) {
    return stages.define(goal, version, plan);
  }

  /** 目標の達成管理に関する関連付けを処理する。 */
  public void attach(
      GoalRepository.Goal goal, String stage, long version, GoalCompletionGate.Proof proof) {
    stages.attachProof(goal, stage, version, proof);
  }

  /** 目標の達成管理で用いるowning実行を返す。 */
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

  /** 後続の判断で選べる候補を用意するため、分割を提案する。 */
  public void proposeSplit(
      dev.mikoto2000.rei.core.chat.AgentRunContext owner,
      String id,
      long version,
      List<GoalStageDefinition> children) {
    if (!allowSplit) throw new IllegalStateException("Stage splitting is disabled");
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

  /** 目標の達成管理に関する関連付け実行中を処理する。 */
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

  /** Observation for the existing proof tool; this does not mark a Stage complete. */
  public java.util.Map<String, Object> inspectRunning(
      dev.mikoto2000.rei.core.chat.AgentRunContext owner, String id) {
    var goal = owningRun(owner, id);
    var attempt = stages.forRun(goal, owner.runId());
    if (attempt == null) throw new IllegalStateException("Current Stage required");
    return java.util.Map.of(
        "stageId",
        attempt.stageId(),
        "planVersion",
        attempt.planVersion(),
        "observation",
        observeRun(goal, owner.runId()));
  }

  /** 目標の達成管理において、反映提案に該当するか判定する。 */
  public boolean applyProposal(GoalRepository.Claim claim, String run) {
    return allowSplit && stages.applyProposal(claim, run);
  }
}
