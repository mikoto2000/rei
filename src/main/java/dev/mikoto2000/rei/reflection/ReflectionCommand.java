package dev.mikoto2000.rei.reflection;

import dev.mikoto2000.rei.core.project.ProjectService;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/** 保存された目標・タスク・実行の証拠とレビュー提案を確認しますを行うための利用者コマンド。 */
@Component
@Command(name = "reflection", description = "保存された目標・タスク・実行の証拠とレビュー提案を確認します")
public class ReflectionCommand implements java.util.concurrent.Callable<Integer> {
  /** 保存と検索の窓口。 */
  private final GoalReflectionRepository repository;

  /** サービス。 */
  private final GoalReflectionService service;

  /** プロジェクト一覧。 */
  private final ProjectService projects;

  /** 実行振り返り一覧。 */
  private RunReflectionRepository runReflections;

  /** 実行サービス。 */
  private RunReflectionService runService;

  /** 確認済みの教訓を長期記憶へ反映する窓口。 */
  private VerifiedReflectionMemoryService promotion;

  /** 教訓一覧。 */
  private ReflectionLessonService lessons;

  /** 作業の振り返りで使用する教訓一覧を設定する。 */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setLessons(ReflectionLessonService lessons) {
    this.lessons = lessons;
  }

  /** 改訂番号。 */
  @Option(names = "--revision", defaultValue = "-1")
  long revision;

  /** 根拠ハッシュ値。 */
  @Option(names = "--evidence-hash")
  String evidenceHash;

  /** 補足。 */
  @Option(names = "--note")
  String note;

  /** 取得元種類。 */
  @Option(names = "--source-kind", defaultValue = "RUN")
  String sourceKind;

  /** 種類。 */
  @Option(names = "--kind", defaultValue = "FAILURE_PATTERN")
  ReflectionLessonService.Kind kind;

  /** 根拠。 */
  @Option(names = "--evidence", split = ",")
  java.util.List<String> evidence;

  /** 観測内容。 */
  @Option(names = "--observation")
  String observation;

  /** 作業の振り返りで使用するPromotionを設定する。 */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setPromotion(VerifiedReflectionMemoryService promotion) {
    this.promotion = promotion;
  }

  /** 作業の振り返りで使用する実行サービスを設定する。 */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setRunService(RunReflectionService runService) {
    this.runService = runService;
  }

  /** 作業の振り返りで使用する実行振り返り一覧を設定する。 */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setRunReflections(RunReflectionRepository runReflections) {
    this.runReflections = runReflections;
  }

  /** 仕様。 */
  @Spec picocli.CommandLine.Model.CommandSpec spec;

  /** 操作。 */
  @Parameters(
      index = "0",
      arity = "0..1",
      defaultValue = "list",
      paramLabel = "list|show|collect|runs|run|collect-run|promote",
      completionCandidates = ActionCandidates.class)
  String action;

  /** 識別子。 */
  @Parameters(index = "1", arity = "0..1", paramLabel = "reflectionId|goalId")
  String id;

  /** 出力。 */
  private java.io.PrintWriter output;

  /** 作業の振り返りを利用するため、指定を補って共通の初期化へ引き渡す。 */
  public ReflectionCommand() {
    this(null, null, null);
  }

  /** 指定された値や利用先を使って、ReflectionCommandを初期化する。 */
  @org.springframework.beans.factory.annotation.Autowired
  public ReflectionCommand(
      GoalReflectionRepository repository, GoalReflectionService service, ProjectService projects) {
    this.repository = repository;
    this.service = service;
    this.projects = projects;
  }

  /** 利用者へ結果を案内するターミナルの出力先を設定する。 */
  public void setShellOutput(java.io.PrintWriter output) {
    this.output = output;
  }

  /** 保存された目標・タスク・実行の証拠とレビュー提案を確認しますを行う。 */
  @Override
  public Integer call() {
    var writer = output == null ? spec.commandLine().getOut() : output;
    try {
      String project = projects.currentContext().id();
      Object result =
          switch (action) {
            case "list" -> repository.list(project);
            case "show" -> repository.get(project, requiredId());
            case "collect" -> service.collect(project, requiredId());
            case "runs" -> runReflections.list(project);
            case "run" -> runReflections.get(project, requiredId());
            case "collect-run" ->
                runService.collect(project, projects.currentSessionId(), requiredId());
            case "promote" -> {
              if (promotion == null)
                throw new IllegalStateException("Verified memory promotion is unavailable");
              yield promotion.promote(project, requiredId());
            }
            case "lessons" -> lessonService().list(lessonOwner());
            case "lesson" -> lessonService().get(lessonOwner(), requiredId());
            case "lesson-observe" ->
                lessonService().observe(lessonOwner(), sourceKind, requiredId());
            case "lesson-observe-correction" ->
                lessonService().observeCorrection(lessonOwner(), requiredId(), observation, note);
            case "lesson-propose" -> lessonService().propose(lessonOwner(), kind, note, evidence);
            case "lesson-validate" ->
                lessonService().validate(lessonOwner(), requiredId(), revision, evidenceHash, note);
            case "lesson-counterexample" ->
                lessonService()
                    .counterexample(lessonOwner(), requiredId(), revision, observation, note);
            case "lesson-correct" ->
                lessonService().correct(lessonOwner(), requiredId(), revision, note);
            case "lesson-forget" -> lessonService().forget(lessonOwner(), requiredId(), revision);
            default ->
                throw new IllegalArgumentException(
                    "Use /reflection list|show|collect|runs|run|collect-run|promote [id]");
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

  /** 作業の振り返りで用いる必須識別子を返す。 */
  private String requiredId() {
    if (id == null || id.isBlank())
      throw new IllegalArgumentException("Reflection ID or Goal ID is required");
    return id;
  }

  /** 作業の振り返りで用いる教訓サービスを返す。 */
  private ReflectionLessonService lessonService() {
    if (lessons == null) throw new IllegalStateException("Reflection lessons are disabled");
    return lessons;
  }

  /** 作業の振り返りで用いる教訓所有者を返す。 */
  private dev.mikoto2000.rei.core.chat.AgentRunContext lessonOwner() {
    var project = projects.currentContext();
    return new dev.mikoto2000.rei.core.chat.AgentRunContext(
        "human-reflection", projects.currentSessionId(), project.root(), project.id());
  }

  /** 操作で利用できる入力候補を提供する。 */
  public static final class ActionCandidates
      extends dev.mikoto2000.rei.core.completion.FirstArgumentCandidates {
    /** 既定の状態でActionCandidatesを初期化する。 */
    public ActionCandidates() {
      super(
          "list",
          "show",
          "collect",
          "runs",
          "run",
          "collect-run",
          "promote",
          "lessons",
          "lesson",
          "lesson-observe",
          "lesson-observe-correction",
          "lesson-propose",
          "lesson-validate",
          "lesson-counterexample",
          "lesson-correct",
          "lesson-forget");
    }
  }
}
