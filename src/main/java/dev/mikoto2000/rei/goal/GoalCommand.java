package dev.mikoto2000.rei.goal;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.project.ProjectService;
import dev.mikoto2000.rei.output.CommandOutput;
import java.util.UUID;
import java.util.function.BiFunction;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/** ファイルの達成目標を保存し、独立した検証と上限付きの対話計画を行いますを行うための利用者コマンド。 */
@Component
@Command(name = "goal", description = "ファイルの達成目標を保存し、独立した検証と上限付きの対話計画を行います")
public class GoalCommand implements java.util.concurrent.Callable<Integer> {
  /** 目標一覧。 */
  private final GoalRepository goals;

  /** 繰り返し。 */
  private final BiFunction<String, String, GoalCompletionProgress> readProgress;

  private final BiFunction<String, String, GoalStageService.Progress> readStages;
  private final BiFunction<String, String, GoalLoopService.Inspection> verifyGoal;
  private final BiFunction<String, String, GoalRepository.Goal> runGoal;
  private final BiFunction<String, String, GoalRepository.Goal> cancelGoal;
  private final BiFunction<GoalKey, String, GoalRepository.Goal> reconcileGoal;

  private record GoalKey(String project, String id) {}

  private final BiFunction<GoalKey, StagePlan, java.util.List<GoalStageRepository.Stage>>
      defineStagePlan;
  private final BiFunction<GoalKey, StageEvidence, GoalStageService.Progress> attachStageProof;
  private final BiFunction<AgentRunContext, CompletionChange, GoalRepository.Goal> changeCompletion;
  private final BiFunction<AgentRunContext, Creation, GoalRepository.Goal> createGoal;

  private record StagePlan(long version, java.util.List<GoalStageDefinition> plan) {}

  private record StageEvidence(String stage, long version, GoalCompletionGate.Proof proof) {}

  private record CompletionChange(
      String goal, GoalCompletionGate.Definition definition, GoalCompletionGate.Proof proof) {}

  private record Creation(
      String objective,
      String file,
      String digest,
      java.util.List<GoalRepository.FileCriterion> criteria,
      GoalCompletionGate.Definition definition,
      int runs,
      int calls) {}

  /** プロジェクト一覧。 */
  private record SelectedProject(String id, java.nio.file.Path root) {}

  private final java.util.function.Supplier<SelectedProject> selectedProject;

  private final java.util.function.Supplier<String> currentSessionId;

  /** 待機一覧。 */
  private GoalWaitService waits;

  /** 目標の達成管理に関する設定待機一覧を処理する。 */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void configureWaits(GoalWaitService waits) {
    this.waits = waits;
  }

  /** 目標の達成管理で用いる待機一覧を返す。 */
  private GoalWaitService waits() {
    if (waits == null) throw new IllegalStateException("Goal waiting unavailable");
    return waits;
  }

  /** 依存条件識別子。 */
  @Option(names = "--dependency-id")
  String dependencyId;

  /** 待機理由。 */
  @Option(names = "--wait-reason", defaultValue = "dependency_wait")
  String waitReason;

  /** 待機形式のバージョン。 */
  @Option(names = "--wait-version")
  Long waitVersion;

  /** 必要な状態が整ってから先へ進むため、形式のバージョンを待つ。 */
  private long waitVersion() {
    if (waitVersion == null || waitVersion < 1)
      throw new IllegalArgumentException("Use the observed --wait-version");
    return waitVersion;
  }

  /** 仕様。 */
  @Spec picocli.CommandLine.Model.CommandSpec spec;

  /** 操作。 */
  @Parameters(
      index = "0",
      arity = "0..1",
      defaultValue = "list",
      paramLabel =
          "list|create|show|progress|run|verify|cancel|history|reconcile|completion|wait|wait-activate|wait-resume|wait-cancel",
      completionCandidates = ActionCandidates.class)
  String action;

  /** 値。 */
  @Parameters(index = "1", arity = "0..1", paramLabel = "goalId|objective")
  String value;

  /** ファイル。 */
  @Option(names = "--file", description = "プロジェクトからの相対パスで指定する完了確認用ファイル")
  String file;

  /** 内容の照合値。 */
  @Option(names = "--sha256", description = "ファイルに期待する正確な SHA-256 値")
  String digest;

  /** 判断基準一覧JSON変換。 */
  @Option(
      names = "--criteria-json",
      description =
          "{relativeFile,sha256} または {relativeFile,jsonPointer,expectedJson} 形式のスカラー値の条件を1〜16件指定する JSON 配列（全条件の一致が必要）")
  String criteriaJson;

  /** 完了条件JSON変換。 */
  @Option(names = "--completion-json", description = "ユーザーが定義する完了条件：根拠、必須のテスト・成果物・判定条件、レビュー要件")
  String completionJson;

  /** 確認の根拠JSON変換。 */
  @Option(names = "--proof-json", description = "停止した目標に対する保存済みレビューと成果物の正確な ID／SHA 参照")
  String proofJson;

  /** 段階一覧JSON変換。 */
  @Option(names = "--stages-json")
  String stagesJson;

  /** 計画形式のバージョン。 */
  @Option(names = "--plan-version")
  Long planVersion;

  /** 段階識別子。 */
  @Option(names = "--stage-id")
  String stageId;

  /** 段階形式のバージョン。 */
  @Option(names = "--stage-version")
  Long stageVersion;

  /** 期待する内容実行の識別子。 */
  @Option(names = "--run-id", description = "結果が不明な実行の ID（実行を試みていない場合は none）")
  String expectedRunId;

  /** acknowledge不確実な。 */
  @Option(
      names = "--acknowledge-uncertain-side-effects",
      description = "明示的に再開する前に、前回の実行による影響の確認が必要であることを承認します")
  boolean acknowledgeUncertain;

  /** 最大実行一覧。 */
  @Option(names = "--max-runs", defaultValue = "3")
  int maxRuns;

  /** 最大呼び出し回数。 */
  @Option(names = "--max-llm-calls", defaultValue = "20")
  int maxCalls;

  /** 出力。 */
  private CommandOutput output;

  /** 目標の達成管理を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public GoalCommand() {
    this(null, null, null);
  }

  /** 指定された値や利用先を使って、GoalCommandを初期化する。 */
  @org.springframework.beans.factory.annotation.Autowired
  public GoalCommand(GoalRepository goals, GoalLoopService loop, ProjectService projects) {
    this.goals = goals;
    this.readProgress = (project, id) -> loop.progress(project, id);
    this.readStages = (project, id) -> loop.stageProgress(project, id);
    this.verifyGoal = (project, id) -> loop.verify(project, id);
    this.runGoal = (project, id) -> loop.run(project, id);
    this.cancelGoal = (project, id) -> loop.cancel(project, id);
    this.reconcileGoal = (key, run) -> loop.reconcile(key.project(), key.id(), run);
    this.defineStagePlan =
        (key, plan) -> loop.defineStages(key.project(), key.id(), plan.version(), plan.plan());
    this.attachStageProof =
        (key, evidence) ->
            loop.attachStageEvidence(
                key.project(), key.id(), evidence.stage(), evidence.version(), evidence.proof());
    this.changeCompletion =
        (owner, change) ->
            change.definition() != null
                ? loop.defineCompletion(owner, change.goal(), change.definition())
                : loop.attachCompletion(owner, change.goal(), change.proof());
    this.createGoal =
        (owner, request) -> {
          if (request.definition() != null)
            return loop.create(
                owner,
                request.objective(),
                request.criteria(),
                request.definition(),
                request.runs(),
                request.calls());
          if (request.criteria() == null)
            return loop.create(
                owner,
                request.objective(),
                request.file(),
                request.digest(),
                request.runs(),
                request.calls());
          return loop.create(
              owner, request.objective(), request.criteria(), request.runs(), request.calls());
        };
    this.selectedProject =
        () -> {
          var project = projects.currentContext();
          return new SelectedProject(project.id(), project.root());
        };
    this.currentSessionId = () -> projects.currentSessionId();
  }

  /** 利用者へ結果を案内するターミナルの出力先を設定する。 */
  public void setShellOutput(java.io.PrintWriter output) {
    this.output = CommandOutput.from(output);
  }

  /** ファイルの達成目標を保存し、独立した検証と上限付きの対話計画を行いますを行う。 */
  @Override
  public Integer call() {
    var writer = output == null ? CommandOutput.from(spec.commandLine().getOut()) : output;
    try {
      var project = selectedProject.get();
      Object result =
          switch (action) {
            case "list" -> goals.list(project.id());
            case "show" -> goals.get(project.id(), requiredValue());
            case "progress" -> readProgress.apply(project.id(), requiredValue());
            case "stages" -> {
              if (stagesJson == null) yield readStages.apply(project.id(), requiredValue());
              if (planVersion == null || planVersion < 0)
                throw new IllegalArgumentException("Observed --plan-version required");
              var goal = goals.get(project.id(), requiredValue());
              if (!goal.sessionId().equals(currentSessionId.get()))
                throw new IllegalArgumentException("Select owning Goal Session");
              yield defineStagePlan.apply(
                  new GoalKey(project.id(), goal.id()),
                  new StagePlan(planVersion, GoalStageRepository.parsePlan(stagesJson)));
            }
            case "stage-evidence" -> {
              if (stageId == null || stageVersion == null || stageVersion < 1 || proofJson == null)
                throw new IllegalArgumentException(
                    "--stage-id, --stage-version, --proof-json required");
              var goal = goals.get(project.id(), requiredValue());
              if (!goal.sessionId().equals(currentSessionId.get()))
                throw new IllegalArgumentException("Select owning Goal Session");
              yield attachStageProof.apply(
                  new GoalKey(project.id(), goal.id()),
                  new StageEvidence(
                      stageId, stageVersion, GoalRepository.parseCompletionProof(proofJson)));
            }
            case "history" ->
                goals.history(project.id(), requiredValue())
                    + "\nAttempts: "
                    + goals.attempts(project.id(), requiredValue());
            case "wait" -> {
              var goal = goals.get(project.id(), requiredValue());
              if (!goal.sessionId().equals(currentSessionId.get()))
                throw new IllegalArgumentException("Select the owning Goal Session");
              yield dependencyId == null
                  ? waits().show(project.id(), goal.id())
                  : waits().waitFor(project.id(), goal.id(), dependencyId, waitReason);
            }
            case "wait-activate" -> waits().activate(project.id(), requiredValue(), waitVersion());
            case "wait-resume" -> waits().resume(project.id(), requiredValue(), waitVersion());
            case "wait-cancel" -> waits().cancel(project.id(), requiredValue(), waitVersion());
            case "run" -> runGoal.apply(project.id(), requiredValue());
            case "verify" -> verifyGoal.apply(project.id(), requiredValue());
            case "reconcile" -> {
              if (!acknowledgeUncertain || expectedRunId == null || expectedRunId.isBlank())
                throw new IllegalArgumentException(
                    "Reconcile requires --run-id and --acknowledge-uncertain-side-effects; inspect current effects before resuming");
              yield reconcileGoal.apply(
                  new GoalKey(project.id(), requiredValue()),
                  expectedRunId.equals("none") ? null : expectedRunId);
            }
            case "cancel" -> cancelGoal.apply(project.id(), requiredValue());
            case "completion" -> {
              var goal = goals.get(project.id(), requiredValue());
              var owner =
                  new AgentRunContext(
                      UUID.randomUUID().toString(),
                      currentSessionId.get(),
                      project.root(),
                      project.id());
              if ((completionJson == null) == (proofJson == null))
                throw new IllegalArgumentException("Use exactly --completion-json or --proof-json");
              yield changeCompletion.apply(
                  owner,
                  completionJson != null
                      ? new CompletionChange(
                          goal.id(), GoalRepository.parseCompletion(completionJson), null)
                      : new CompletionChange(
                          goal.id(), null, GoalRepository.parseCompletionProof(proofJson)));
            }
            case "create" -> {
              String session = currentSessionId.get();
              if (session == null || session.isBlank())
                throw new IllegalArgumentException(
                    "Create or select a Session before creating a Goal");
              var owner =
                  new AgentRunContext(
                      UUID.randomUUID().toString(), session, project.root(), project.id());
              if (proofJson != null)
                throw new IllegalArgumentException(
                    "Attach proof to an existing Goal with completion");
              String objective = requiredValue();
              var criteria =
                  criteriaJson != null
                      ? parseCriteria()
                      : completionJson == null
                          ? null
                          : java.util.List.of(new GoalRepository.FileCriterion(file, digest));
              var definition =
                  completionJson == null ? null : GoalRepository.parseCompletion(completionJson);
              yield createGoal.apply(
                  owner,
                  new Creation(objective, file, digest, criteria, definition, maxRuns, maxCalls));
            }
            default ->
                throw new IllegalArgumentException(
                    "Use /goal list|create|show|progress|run|verify|cancel|history|reconcile|completion|wait|wait-activate|wait-resume|wait-cancel");
          };
      writer.println(dev.mikoto2000.rei.event.CredentialRedactor.redact(String.valueOf(result)));
      return 0;
    } catch (RuntimeException error) {
      writer.println("[error] " + dev.mikoto2000.rei.event.FailureMessages.describe(error));
      return 2;
    } finally {
      writer.flush();
    }
  }

  /** 入力を機能内で扱えるよう、判断基準一覧を解釈する。 */
  private java.util.List<GoalRepository.FileCriterion> parseCriteria() {
    // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
    if (file != null || digest != null || criteriaJson.length() > 32768)
      throw new IllegalArgumentException(
          "Use either --criteria-json (up to 32768 characters) or --file and --sha256");
    return JsonFileGoalCondition.parseCriteria(criteriaJson);
  }

  /** 目標の達成管理で用いる必須値を返す。 */
  private String requiredValue() {
    if (value == null || value.isBlank())
      throw new IllegalArgumentException("Goal ID or objective is required");
    return value;
  }

  /** 操作で利用できる入力候補を提供する。 */
  public static final class ActionCandidates
      extends dev.mikoto2000.rei.core.completion.FirstArgumentCandidates {
    /** 既定の状態でActionCandidatesを初期化する。 */
    public ActionCandidates() {
      super(
          "list",
          "create",
          "show",
          "progress",
          "stages",
          "stage-evidence",
          "run",
          "verify",
          "cancel",
          "history",
          "reconcile",
          "completion",
          "wait",
          "wait-activate",
          "wait-resume",
          "wait-cancel");
    }
  }
}
