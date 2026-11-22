package dev.mikoto2000.rei.checkpoint;

import dev.mikoto2000.rei.core.project.ProjectService;
import dev.mikoto2000.rei.output.CommandOutput;
import java.util.concurrent.Callable;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

/** 保存タスクの一覧・照合・明示再開・除外を行うための利用者コマンド。 */
@Component
@Command(
    name = "resume",
    description = "保存タスクの一覧・照合・明示再開・除外",
    subcommands = {
      ResumeCommand.ListTasks.class,
      ResumeCommand.Show.class,
      ResumeCommand.Abandon.class,
      ResumeCommand.Overview.class
    })
public class ResumeCommand implements Callable<Integer> {
  /** サービス。 */
  private final java.util.function.Function<String, java.util.List<PersistentCheckpoint>>
      listCheckpoints;

  private final java.util.function.BiFunction<String, String, PersistentCheckpoint> findCheckpoint;
  private final java.util.function.BiFunction<String, String, CheckpointReconciler.Result>
      inspectCheckpoint;
  private final java.util.function.BiFunction<String, String, PersistentCheckpoint>
      abandonCheckpoint;

  @FunctionalInterface
  private interface ResumeFromShell {
    PersistentCheckpointService.ResumeResult resume(
        String project, String task, java.util.function.Consumer<String> beforeSubmit);
  }

  private final ResumeFromShell resumeFromShell;

  /** プロジェクト一覧。 */
  private final java.util.function.Supplier<String> currentProjectId;

  private final java.util.function.Supplier<String> currentSessionId;

  private final java.util.function.Consumer<String> selectSession;

  /** キャンセル。 */
  private final java.util.function.Supplier<Runnable> beginCommand;

  /** 仕様。 */
  @Spec CommandSpec spec;

  /** タスク。 */
  @Parameters(index = "0", arity = "0..1", paramLabel = "taskId")
  String task;

  @picocli.CommandLine.Option(
      names = "--status",
      description = "running, waiting, waiting_approval, completed, failed, cancelled, interrupted")
  String status;

  @picocli.CommandLine.Option(names = "--all", description = "全プロジェクトの再開候補を表示")
  boolean all;

  private java.util.function.Supplier<java.util.List<String>> registeredProjects;
  private java.util.function.Consumer<String> selectProject;
  private java.util.function.Function<String, String> projectName;

  /** 出力。 */
  private CommandOutput output;

  /** 概要。 */
  private ResumeOverviewService overview;

  private java.util.function.Function<
          java.util.List<PersistentCheckpoint>, java.util.Optional<String>>
      taskSelection;

  /** Local interactive selection; batch and API callers retain their existing behavior. */
  public void setTaskSelection(
      java.util.function.Function<java.util.List<PersistentCheckpoint>, java.util.Optional<String>>
          selection) {
    taskSelection = selection;
  }

  /** Present primitive IDs and redacted labels without exposing checkpoint models to wiring. */
  public void setTaskMenu(
      java.util.function.BiFunction<
              java.util.List<String>, java.util.List<String>, java.util.Optional<String>>
          menu) {
    setTaskSelection(items ->
        menu.apply(
            items.stream()
                .map(item -> all ? item.projectId() + ":" + item.taskId() : item.taskId())
                .toList(),
            items.stream()
                .map(item ->
                    ResumeOverview.safe(
                        (all ? projectName.apply(item.projectId()) + " / " : "")
                            + "["
                            + item.status()
                            + "] "
                            + item.request()
                            + " ("
                            + item.taskId()
                            + ")",
                        240))
                .toList()));
  }

  /** 作業の中断と再開で使用する概要を設定する。 */
  @org.springframework.beans.factory.annotation.Autowired
  public void setOverview(ResumeOverviewService value) {
    overview = value;
  }

  /** 作業の中断と再開を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public ResumeCommand() {
    this(null, null, new dev.mikoto2000.rei.core.service.CommandCancellationService());
  }

  /** 作業の中断と再開を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public ResumeCommand(PersistentCheckpointService service, ProjectService projects) {
    this(service, projects, new dev.mikoto2000.rei.core.service.CommandCancellationService());
  }

  /** 指定された値や利用先を使って、ResumeCommandを初期化する。 */
  @org.springframework.beans.factory.annotation.Autowired
  public ResumeCommand(
      PersistentCheckpointService service,
      ProjectService projects,
      dev.mikoto2000.rei.core.service.CommandCancellationService cancellation) {
    this.listCheckpoints = project -> service.list(project);
    this.findCheckpoint = (project, task) -> service.get(project, task);
    this.inspectCheckpoint = (project, task) -> service.inspect(project, task);
    this.abandonCheckpoint = (project, task) -> service.abandon(project, task);
    this.resumeFromShell = (project, task, before) -> service.resumeForShell(project, task, before);
    this.registeredProjects =
        () -> projects.registeredProjects().stream().map(p -> p.id()).toList();
    this.projectName =
        id ->
            projects.registeredProjects().stream()
                .filter(p -> p.id().equals(id))
                .map(p -> p.name())
                .findFirst()
                .orElse(id);
    this.selectProject =
        id -> {
          var project =
              projects.registeredProjects().stream()
                  .filter(p -> p.id().equals(id))
                  .findFirst()
                  .orElseThrow(() -> new IllegalArgumentException("Unknown project"));
          projects.cd(project.root().toString());
        };
    this.currentProjectId = () -> projects.currentContext().id();
    this.currentSessionId = () -> projects.currentSessionId();
    this.selectSession = session -> projects.selectSession(session);
    this.beginCommand =
        () -> {
          cancellation.begin(Thread.currentThread());
          return cancellation::clear;
        };
  }

  /** 利用者へ結果を案内するターミナルの出力先を設定する。 */
  public void setShellOutput(java.io.PrintWriter output) {
    this.output = CommandOutput.from(output);
  }

  /** 利用者に結果を伝えるため、再開コマンドを出力する。 */
  int print(java.util.function.Supplier<Object> action) {
    var writer = output == null ? CommandOutput.from(spec.commandLine().getOut()) : output;
    Runnable clearCancellation = beginCommand.get();
    try {
      CheckpointReconciler.active();
      writer.println(action.get());
      return 0;
    } catch (RuntimeException e) {
      writer.println("[error] " + dev.mikoto2000.rei.event.FailureMessages.describe(e));
      return Thread.currentThread().isInterrupted() ? 130 : 2;
    } finally {
      clearCancellation.run();
      writer.flush();
    }
  }

  /** 作業の中断と再開で用いるプロジェクトを返す。 */
  String project() {
    return currentProjectId.get();
  }

  /** 保存タスクの一覧・照合・明示再開・除外を行う。 */
  @Override
  public Integer call() {
    return print(() -> {
      String selectedStatus = dev.mikoto2000.rei.runmanagement.ListingRequest.status(status, false);
      if (task != null) {
        if (all || status != null)
          throw new IllegalArgumentException(
              "Use --status and --all with the candidate list, not a task ID");
        return resumeTask(project(), task);
      }
      var candidates = candidates(selectedStatus);
      if (taskSelection == null) {
        if (!all && status == null) return candidates;
        if (candidates.isEmpty()) return "再開候補はありません。";
        return candidates.stream()
            .map(item ->
                ResumeOverview.safe(
                    projectName.apply(item.projectId())
                        + " / "
                        + item.taskId()
                        + " ["
                        + item.status()
                        + "] rev "
                        + item.revision()
                        + " / "
                        + item.createdAt()
                        + " / "
                        + item.request(),
                    400))
            .collect(java.util.stream.Collectors.joining("\n"));
      }
      if (candidates.isEmpty()) return "再開候補はありません。";
      var selected = taskSelection.apply(candidates);
      if (selected.isEmpty()) return "再開せずに閉じました。";
      var matches =
          candidates.stream()
              .filter(c ->
                  (all ? c.projectId() + ":" + c.taskId() : c.taskId()).equals(selected.get())
                      || c.taskId().equals(selected.get()))
              .toList();
      if (matches.size() != 1) throw new IllegalArgumentException("Unknown or ambiguous candidate");
      var owner = matches.getFirst();
      return resumeTask(all ? owner.projectId() : project(), owner.taskId());
    });
  }

  /** 再開した実行の出力と後続の入力を同じ会話へ向ける。 */
  private java.util.List<PersistentCheckpoint> candidates(String selectedStatus) {
    var candidates =
        all
            ? registeredProjects.get().stream()
                .flatMap(p -> listCheckpoints.apply(p).stream())
                .toList()
            : listCheckpoints.apply(project());
    if (!all && selectedStatus == null) return candidates;
    return candidates.stream()
        .filter(c -> selectedStatus == null || c.status().equalsIgnoreCase(selectedStatus))
        .sorted(
            java.util.Comparator.comparing(PersistentCheckpoint::createdAt)
                .reversed()
                .thenComparing(PersistentCheckpoint::taskId))
        .toList();
  }

  private PersistentCheckpointService.ResumeResult resumeTask(
      String targetProject, String selectedTask) {
    String previousSession = currentSessionId.get();
    String previousProject = project();
    boolean[] selected = {false};
    try {
      return resumeFromShell.resume(
          targetProject,
          selectedTask,
          sessionId -> {
            selected[0] = true;
            if (!targetProject.equals(previousProject)) selectProject.accept(targetProject);
            selectSession.accept(sessionId);
          });
    } catch (RuntimeException | Error error) {
      if (selected[0]) {
        if (!targetProject.equals(previousProject)) selectProject.accept(previousProject);
        selectSession.accept(previousSession);
      }
      throw error;
    }
  }

  /** 作業の中断と再開に必要な一覧タスク一覧を扱う。 */
  @Command(name = "list", description = "現在 Project の保存済みタスクと状態を一覧表示します")
  public static class ListTasks implements Callable<Integer> {
    /** 親。 */
    @ParentCommand ResumeCommand parent;

    /** 作業の中断と再開の要求を実行し、呼び出し元へ結果を返す。 */
    public Integer call() {
      return parent.print(() ->
          parent.listCheckpoints.apply(parent.project()).stream()
              .map(s ->
                  s.taskId()
                      + " ["
                      + s.status()
                      + "] "
                      + PersistentCheckpointService.bounded(s.request(), 160))
              .toList());
    }
  }

  /** 作業の中断と再開に必要な概要を扱う。 */
  @Command(name = "overview", description = "現在 Project の中断作業と再開案内を表示します")
  public static class Overview implements Callable<Integer> {
    /** 親。 */
    @ParentCommand ResumeCommand parent;

    /** 作業の中断と再開の要求を実行し、呼び出し元へ結果を返す。 */
    public Integer call() {
      return parent.print(() ->
          parent
              .overview
              .overview(parent.project())
              .detail(parent.overview.zone(java.time.ZoneId.systemDefault())));
    }
  }

  /** 作業の中断と再開に必要な表示を扱う。 */
  @Command(name = "show", description = "指定タスクのチェックポイントと再開可否を表示します")
  public static class Show implements Callable<Integer> {
    /** 親。 */
    @ParentCommand ResumeCommand parent;

    /** タスク。 */
    @Parameters(index = "0")
    String task;

    /** 作業の中断と再開の要求を実行し、呼び出し元へ結果を返す。 */
    public Integer call() {
      return parent.print(() ->
          parent.findCheckpoint.apply(parent.project(), task)
              + "\n"
              + parent.inspectCheckpoint.apply(parent.project(), task));
    }
  }

  /** 作業の中断と再開に必要なAbandonを扱う。 */
  @Command(name = "abandon", description = "指定した保存済みタスクの再開を取りやめます")
  public static class Abandon implements Callable<Integer> {
    /** 親。 */
    @ParentCommand ResumeCommand parent;

    /** タスク。 */
    @Parameters(index = "0")
    String task;

    /** 作業の中断と再開の要求を実行し、呼び出し元へ結果を返す。 */
    public Integer call() {
      return parent.print(() -> parent.abandonCheckpoint.apply(parent.project(), task));
    }
  }
}
