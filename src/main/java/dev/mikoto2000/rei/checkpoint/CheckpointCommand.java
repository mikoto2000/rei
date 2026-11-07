package dev.mikoto2000.rei.checkpoint;

import dev.mikoto2000.rei.core.project.ProjectService;
import dev.mikoto2000.rei.output.CommandOutput;
import java.util.concurrent.Callable;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

/** 現在のタスクを保存、保存量の確認・過去履歴の整理を行うための利用者コマンド。 */
@Component
@Command(
    name = "checkpoint",
    description = "現在のタスクを保存、保存量の確認・過去履歴の整理",
    subcommands = {CheckpointCommand.Status.class, CheckpointCommand.Cleanup.class})
public class CheckpointCommand implements Callable<Integer> {
  /** サービス。 */
  private final java.util.function.Supplier<String> reportUsage;

  private final java.util.function.BiFunction<Boolean, Boolean, String> reportCleanup;
  private final java.util.function.BiFunction<String, String, PersistentCheckpoint> saveCheckpoint;

  /** プロジェクト一覧。 */
  private final java.util.function.Supplier<String> currentProjectId;

  private final java.util.function.Supplier<String> currentSessionId;

  /** 仕様。 */
  @Spec CommandSpec spec;

  /** 出力。 */
  private CommandOutput output;

  /** 作業の中断と再開を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public CheckpointCommand() {
    this(null, null);
  }

  /** 指定された値や利用先を使って、CheckpointCommandを初期化する。 */
  @org.springframework.beans.factory.annotation.Autowired
  public CheckpointCommand(PersistentCheckpointService service, ProjectService projects) {
    this.reportUsage = () -> CheckpointHistoryReport.usage(service.historyUsage());
    this.reportCleanup =
        (apply, force) ->
            CheckpointHistoryReport.cleanup(
                force ? service.cleanupHistory(apply, true) : service.cleanupHistory(apply));
    this.saveCheckpoint = (project, session) -> service.saveCurrent(project, session);
    this.currentProjectId = () -> projects.currentContext().id();
    this.currentSessionId = () -> projects.currentSessionId();
  }

  /** 利用者へ結果を案内するターミナルの出力先を設定する。 */
  public void setShellOutput(java.io.PrintWriter output) {
    this.output = CommandOutput.from(output);
  }

  /** 利用者に結果を伝えるため、再開用の記録コマンドを出力する。 */
  private int print(java.util.function.Supplier<String> action) {
    var writer = output == null ? CommandOutput.from(spec.commandLine().getOut()) : output;
    try {
      writer.println(action.get());
      return 0;
    } catch (RuntimeException error) {
      writer.println("[error] " + dev.mikoto2000.rei.event.FailureMessages.describe(error));
      return 2;
    } finally {
      writer.flush();
    }
  }

  /** 全プロジェクトのチェックポイント保存量と上限を表示ための利用者コマンド。 */
  @Command(name = "status", description = "全プロジェクトのチェックポイント保存量と上限を表示")
  public static class Status implements Callable<Integer> {
    /** 親。 */
    @ParentCommand CheckpointCommand parent;

    /** 全プロジェクトのチェックポイント保存量と上限を表示。 */
    public Integer call() {
      return parent.print(() -> parent.reportUsage.get());
    }
  }

  /** 過去の保存履歴の整理候補を確認。--apply を付けると整理を実行ための利用者コマンド。 */
  @Command(name = "cleanup", description = "過去の保存履歴の整理候補を確認。--apply を付けると整理を実行")
  public static class Cleanup implements Callable<Integer> {
    /** 親。 */
    @ParentCommand CheckpointCommand parent;

    /** 反映。 */
    @Option(names = "--apply", description = "全プロジェクトの整理可能な過去履歴を削除（最新状態・再開・承認続行に必要な履歴は保護）")
    boolean apply;

    /** 強制。 */
    @Option(names = "--force", description = "最新状態・実行中・承認待ちも含む全チェックポイントを対象にする。削除には --apply も必要")
    boolean force;

    /** 過去の保存履歴の整理候補を確認。--apply を付けると整理を実行。 */
    public Integer call() {
      return parent.print(() -> parent.reportCleanup.apply(apply, force));
    }
  }

  /** 現在のタスクを保存、保存量の確認・過去履歴の整理を行う。 */
  public Integer call() {
    var writer = output == null ? CommandOutput.from(spec.commandLine().getOut()) : output;
    try {
      var state = saveCheckpoint.apply(currentProjectId.get(), currentSessionId.get());
      writer.println("Checkpoint saved: task=" + state.taskId() + " revision=" + state.revision());
      return 0;
    } catch (RuntimeException e) {
      writer.println("[error] " + dev.mikoto2000.rei.event.FailureMessages.describe(e));
      return 2;
    } finally {
      writer.flush();
    }
  }
}
