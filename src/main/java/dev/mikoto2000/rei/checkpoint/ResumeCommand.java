package dev.mikoto2000.rei.checkpoint;

import dev.mikoto2000.rei.core.project.ProjectService;
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
  private final PersistentCheckpointService service;

  /** プロジェクト一覧。 */
  private final ProjectService projects;

  /** キャンセル。 */
  private final dev.mikoto2000.rei.core.service.CommandCancellationService cancellation;

  /** 仕様。 */
  @Spec CommandSpec spec;

  /** タスク。 */
  @Parameters(index = "0", arity = "0..1", paramLabel = "taskId")
  String task;

  /** 出力。 */
  private java.io.PrintWriter output;

  /** 概要。 */
  private ResumeOverviewService overview;

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
    this.service = service;
    this.projects = projects;
    this.cancellation = cancellation;
  }

  /** 利用者へ結果を案内するターミナルの出力先を設定する。 */
  public void setShellOutput(java.io.PrintWriter output) {
    this.output = output;
  }

  /** 利用者に結果を伝えるため、再開コマンドを出力する。 */
  int print(java.util.function.Supplier<Object> action) {
    var writer = output == null ? spec.commandLine().getOut() : output;
    cancellation.begin(Thread.currentThread());
    try {
      CheckpointReconciler.active();
      writer.println(action.get());
      return 0;
    } catch (RuntimeException e) {
      writer.println("[error] " + dev.mikoto2000.rei.event.FailureMessages.describe(e));
      return Thread.currentThread().isInterrupted() ? 130 : 2;
    } finally {
      cancellation.clear();
      writer.flush();
    }
  }

  /** 作業の中断と再開で用いるプロジェクトを返す。 */
  String project() {
    return projects.currentContext().id();
  }

  /** 保存タスクの一覧・照合・明示再開・除外を行う。 */
  @Override
  public Integer call() {
    return print(() -> task == null ? service.list(project()) : resumeTask());
  }

  /** 再開した実行の出力と後続の入力を同じ会話へ向ける。 */
  private PersistentCheckpointService.ResumeResult resumeTask() {
    String previousSession = projects.currentSessionId();
    boolean[] selected = {false};
    try {
      return service.resumeForShell(
          project(),
          task,
          sessionId -> {
            projects.selectSession(sessionId);
            selected[0] = true;
          });
    } catch (RuntimeException | Error error) {
      if (selected[0]) projects.selectSession(previousSession);
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
      return parent.print(
          () ->
              parent.service.list(parent.project()).stream()
                  .map(
                      s ->
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
      return parent.print(
          () ->
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
      return parent.print(
          () ->
              parent.service.get(parent.project(), task)
                  + "\n"
                  + parent.service.inspect(parent.project(), task));
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
      return parent.print(() -> parent.service.abandon(parent.project(), task));
    }
  }
}
