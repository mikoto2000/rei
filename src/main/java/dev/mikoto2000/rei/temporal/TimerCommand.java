package dev.mikoto2000.rei.temporal;

import dev.mikoto2000.rei.core.project.ProjectService;
import dev.mikoto2000.rei.output.CommandOutput;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/** 保存された継続実行の予定を確認・有効化しますを行うための利用者コマンド。 */
@Component
@Command(name = "timer", description = "保存された継続実行の予定を確認・有効化します")
public class TimerCommand implements java.util.concurrent.Callable<Integer> {
  private final Function<String, List<PersistentAgentScheduler.Entry>> listSchedules;
  private final BiFunction<String, String, String> describeSchedule;
  private final BiFunction<String, String, List<PersistentAgentScheduler.History>> scheduleHistory;
  private final BiConsumer<String, String> activateSchedule;
  private final BiConsumer<String, String> cancelSchedule;

  /** プロジェクト一覧。 */
  private final Supplier<String> currentProjectId;

  /** 実行先の振り分け。 */
  @FunctionalInterface
  private interface ReconcileSchedule {
    dev.mikoto2000.rei.temporal.PersistentAgentScheduler.Entry apply(
        String project, String id, String expectedRunId);
  }

  private final ReconcileSchedule reconcileSchedule;

  /** 仕様。 */
  @Spec picocli.CommandLine.Model.CommandSpec spec;

  /** 操作。 */
  @Parameters(
      index = "0",
      arity = "0..1",
      defaultValue = "list",
      paramLabel = "list|show|activate|cancel|history|reconcile",
      completionCandidates = ActionCandidates.class)
  String action;

  /** 識別子。 */
  @Parameters(index = "1", arity = "0..1", paramLabel = "timerId")
  String id;

  /** 期待する内容実行の識別子。 */
  @Option(names = "--run-id", description = "結果が不明な実行の ID")
  String expectedRunId;

  /** acknowledge不確実な。 */
  @Option(
      names = "--acknowledge-uncertain-side-effects",
      description = "実行による影響が不明であることを承認します（スケジュールは再実行しません）")
  boolean acknowledgeUncertain;

  /** 出力。 */
  private CommandOutput output;

  /** 時刻と期間の解釈を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public TimerCommand() {
    this(null, null, null);
  }

  /** 時刻と期間の解釈を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public TimerCommand(PersistentAgentScheduler scheduler, ProjectService projects) {
    this(scheduler, projects, null);
  }

  /** 指定された値や利用先を使って、TimerCommandを初期化する。 */
  @org.springframework.beans.factory.annotation.Autowired
  public TimerCommand(
      PersistentAgentScheduler scheduler,
      ProjectService projects,
      AgentScheduleDispatcher dispatcher) {
    this.listSchedules = project -> scheduler.list(project);
    this.describeSchedule =
        (project, id) ->
            scheduler.get(project, id)
                + " interval="
                + scheduler.interval(project, id)
                + " cron="
                + scheduler.cron(project, id)
                + " event="
                + scheduler.eventTrigger(project, id);
    this.scheduleHistory = (project, id) -> scheduler.history(project, id);
    this.activateSchedule = (project, id) -> scheduler.activate(project, id);
    this.cancelSchedule = (project, id) -> scheduler.cancel(project, id);
    this.currentProjectId = () -> projects.currentContext().id();
    this.reconcileSchedule =
        dispatcher == null
            ? null
            : (project, id, expectedRunId) -> dispatcher.reconcile(project, id, expectedRunId);
  }

  /** 利用者へ結果を案内するターミナルの出力先を設定する。 */
  public void setShellOutput(java.io.PrintWriter output) {
    this.output = CommandOutput.from(output);
  }

  /** 保存された継続実行の予定を確認・有効化しますを行う。 */
  @Override
  public Integer call() {
    var writer = output == null ? CommandOutput.from(spec.commandLine().getOut()) : output;
    try {
      String project = currentProjectId.get();
      Object result =
          switch (action) {
            case "list" -> listSchedules.apply(project);
            case "show" -> describeSchedule.apply(project, requiredId());
            case "history" -> scheduleHistory.apply(project, requiredId());
            case "reconcile" -> {
              if (reconcileSchedule == null
                  || !acknowledgeUncertain
                  || expectedRunId == null
                  || expectedRunId.isBlank())
                throw new IllegalArgumentException(
                    "Reconcile requires Run controls, --run-id and --acknowledge-uncertain-side-effects; inspect effects before creating another schedule");
              yield reconcileSchedule.apply(project, requiredId(), expectedRunId);
            }
            case "activate" -> {
              activateSchedule.accept(project, requiredId());
              yield "Schedule activated; dispatch requires rei.agent-scheduler.enabled and rei.tool-permission.enabled";
            }
            case "cancel" -> {
              cancelSchedule.accept(project, requiredId());
              yield "Schedule cancelled";
            }
            default ->
                throw new IllegalArgumentException(
                    "Use /timer list|show|activate|cancel|history|reconcile [timerId]");
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

  /** 時刻と期間の解釈で用いる必須識別子を返す。 */
  private String requiredId() {
    if (id == null || id.isBlank()) throw new IllegalArgumentException("timerId is required");
    return id;
  }

  /** 操作で利用できる入力候補を提供する。 */
  public static final class ActionCandidates
      extends dev.mikoto2000.rei.core.completion.FirstArgumentCandidates {
    /** 既定の状態でActionCandidatesを初期化する。 */
    public ActionCandidates() {
      super("list", "show", "activate", "cancel", "history", "reconcile");
    }
  }
}
