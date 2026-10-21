package dev.mikoto2000.rei.core.command;

import dev.mikoto2000.rei.core.datasource.ReiDataDirectory;
import dev.mikoto2000.rei.storage.StorageInventory;
import java.time.Clock;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

/** 保存量と整理候補を管理しますを行うための利用者コマンド。 */
@Component
@Command(
    name = "storage",
    description = "保存量と整理候補を管理します",
    subcommands = {
      StorageCommand.Status.class,
      StorageCommand.Retention.class,
      StorageCommand.Maintenance.class
    })
public final class StorageCommand {
  /** 計画の作成。 */
  private dev.mikoto2000.rei.storage.RetentionPlanner planner;

  /** 実行先。 */
  private dev.mikoto2000.rei.storage.RetentionExecutor executor;

  /** 保守。 */
  private dev.mikoto2000.rei.storage.StorageMaintenance maintenance;

  /** 自動。 */
  private dev.mikoto2000.rei.storage.AutomaticRetention automatic;

  /** 共通の実行基盤を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public StorageCommand() {
    this(null);
  }

  /** 指定された値や利用先を使って、StorageCommandを初期化する。 */
  @org.springframework.beans.factory.annotation.Autowired
  public StorageCommand(dev.mikoto2000.rei.storage.RetentionPlanner planner) {
    this.planner = planner;
  }

  /** 共通の実行基盤を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public StorageCommand(
      dev.mikoto2000.rei.storage.RetentionPlanner planner,
      dev.mikoto2000.rei.storage.RetentionExecutor executor,
      dev.mikoto2000.rei.storage.StorageMaintenance maintenance,
      dev.mikoto2000.rei.storage.AutomaticRetention automatic) {
    this(planner);
    setExecutionServices(executor, maintenance, automatic);
  }

  /** 共通の実行基盤で使用する実行サービス一覧を設定する。 */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setExecutionServices(
      dev.mikoto2000.rei.storage.RetentionExecutor executor,
      dev.mikoto2000.rei.storage.StorageMaintenance maintenance,
      dev.mikoto2000.rei.storage.AutomaticRetention automatic) {
    this.executor = executor;
    this.maintenance = maintenance;
    this.automatic = automatic;
  }

  /** 共通の実行基盤で用いるレポート計画を返す。 */
  public String reportPlan(String category, String project, int objects, long bytes) {
    var action = new Plan();
    action.category = category;
    action.project = project;
    action.objects = objects;
    action.bytes = bytes;
    return reportRetention(action);
  }

  /** 共通の実行基盤で用いるレポート承認を返す。 */
  public String reportApprove(String id) {
    var action = new Approve();
    action.id = id;
    return reportRetention(action);
  }

  /** 共通の実行基盤で用いるレポート保持期間履歴を返す。 */
  public String reportRetentionHistory() {
    return reportRetention(new History());
  }

  /** 共通の実行基盤で用いるレポート反映を返す。 */
  public String reportApply(String id) {
    var action = new Apply();
    action.id = id;
    return reportRetention(action);
  }

  /** 共通の実行基盤で用いるレポート復元を返す。 */
  public String reportRestore(String id) {
    var action = new Restore();
    action.id = id;
    return reportRetention(action);
  }

  /** 共通の実行基盤で用いるレポート承認保持期限を過ぎた情報の削除を返す。 */
  public String reportApprovePurge(String id) {
    var action = new ApprovePurge();
    action.id = id;
    return reportRetention(action);
  }

  /** 共通の実行基盤で用いるレポート保持期限を過ぎた情報の削除を返す。 */
  public String reportPurge(String id) {
    var action = new Purge();
    action.id = id;
    return reportRetention(action);
  }

  /** 共通の実行基盤で用いるレポート判断基準を返す。 */
  public String reportPolicy(
      String category,
      String days,
      Long count,
      Long bytes,
      boolean clearCount,
      boolean clearBytes) {
    var action = new Policy();
    action.category = category;
    action.days = days;
    action.count = count;
    action.bytes = bytes;
    action.clearCount = clearCount;
    action.clearBytes = clearBytes;
    return reportRetention(action);
  }

  /** 共通の実行基盤で用いるレポート自動提案を返す。 */
  public String reportAutoPropose(
      String category, String project, int objects, long bytes, long seconds) {
    var action = new AutoPropose();
    action.category = category;
    action.project = project;
    action.objects = objects;
    action.bytes = bytes;
    action.seconds = seconds;
    return reportAuto(action);
  }

  /** 共通の実行基盤で用いるレポート自動承認を返す。 */
  public String reportAutoApprove(String id) {
    var action = new AutoApprove();
    action.id = id;
    return reportAuto(action);
  }

  /** 共通の実行基盤で用いるレポート自動無効化を返す。 */
  public String reportAutoDisable(String id) {
    var action = new AutoDisable();
    action.id = id;
    return reportAuto(action);
  }

  /** 共通の実行基盤で用いるレポート自動履歴を返す。 */
  public String reportAutoHistory() {
    return reportAuto(new AutoHistory());
  }

  /** 共通の実行基盤で用いるレポート保守状態を返す。 */
  public String reportMaintenanceStatus() {
    return reportMaintenance(new MaintenanceStatus());
  }

  /** 共通の実行基盤で用いるレポート再開用の記録を返す。 */
  public String reportCheckpoint() {
    return reportMaintenance(new Checkpoint());
  }

  /** 共通の実行基盤で用いるレポートIncremental保存領域の整理を返す。 */
  public String reportIncrementalVacuum(int pages) {
    var action = new IncrementalVacuum();
    action.pages = pages;
    return reportMaintenance(action);
  }

  /** 共通の実行基盤で用いるレポート保存領域の整理を返す。 */
  public String reportVacuum(long seconds) {
    var action = new Vacuum();
    action.seconds = seconds;
    return reportMaintenance(action);
  }

  /** 共通の実行基盤で用いるレポート状態を返す。 */
  public String reportStatus(String project) {
    var action = new Status();
    var text = new java.io.StringWriter();
    var writer = new java.io.PrintWriter(text, true);
    new picocli.CommandLine(action).setOut(writer).setErr(writer);
    action.parent = this;
    action.project = project;
    action.call();
    writer.flush();
    return reportText(text);
  }

  /** 共通の実行基盤で用いるレポート保持期間を返す。 */
  private String reportRetention(RetentionAction action) {
    action.parent = new Retention();
    action.parent.parent = this;
    return render(
        out -> {
          if (planner == null)
            throw new IllegalStateException("Storage startup gate has not initialized retention");
          action.execute(planner, out);
        });
  }

  /** 共通の実行基盤で用いるレポート自動を返す。 */
  private String reportAuto(AutoAction action) {
    action.parent = new Auto();
    action.parent.parent = new Retention();
    action.parent.parent.parent = this;
    return render(
        out -> {
          if (automatic == null)
            throw new IllegalStateException("Automatic retention has not initialized");
          action.execute(automatic, out);
        });
  }

  /** 共通の実行基盤で用いるレポート保守を返す。 */
  private String reportMaintenance(MaintenanceAction action) {
    action.parent = new Maintenance();
    action.parent.parent = this;
    return render(
        out -> {
          if (maintenance == null)
            throw new IllegalStateException("Storage maintenance has not initialized");
          action.execute(maintenance, out);
        });
  }

  /** 利用者が内容を確認できるよう、表示形式へ変換する。 */
  private static String render(java.util.function.Consumer<java.io.PrintWriter> action) {
    var text = new java.io.StringWriter();
    var out = new java.io.PrintWriter(text, true);
    try {
      action.accept(out);
    } catch (RuntimeException error) {
      out.println("[error] " + dev.mikoto2000.rei.event.FailureMessages.describe(error));
    }
    out.flush();
    return reportText(text);
  }

  /** 共通の実行基盤で用いるレポートテキストを返す。 */
  private static String reportText(java.io.StringWriter text) {
    return text.toString().replaceFirst("[\\r\\n]+$", "");
  }

  /** dry-run、明示承認、隔離・復元。定期整理は既定で無効ですを行うための利用者コマンド。 */
  @Command(
      name = "retention",
      subcommands = {
        Plan.class,
        Approve.class,
        History.class,
        Apply.class,
        Restore.class,
        ApprovePurge.class,
        Purge.class,
        Policy.class,
        Auto.class
      },
      description = "dry-run、明示承認、隔離・復元。定期整理は既定で無効です")
  public static final class Retention {
    /** 親。 */
    @ParentCommand StorageCommand parent;
  }

  /** 共通の実行基盤で用いる選択中対象範囲を返す。 */
  static String selectedScope(String project) {
    if (project == null) return null;
    if (!project.equals("current"))
      throw new IllegalArgumentException("Use --project current or omit it for global scope");
    var context = dev.mikoto2000.rei.core.project.ProjectService.contextForOperation();
    if (context == null)
      throw new IllegalArgumentException("No current project; select a project before planning");
    return context.id();
  }

  /** 共通の実行基盤に必要な保持期間操作を扱う。 */
  @Command
  abstract static class RetentionAction implements java.util.concurrent.Callable<Integer> {
    /** 親。 */
    @ParentCommand Retention parent;

    /** 仕様。 */
    @Spec picocli.CommandLine.Model.CommandSpec spec;

    /** 要求された処理を実行する。 */
    abstract void execute(
        dev.mikoto2000.rei.storage.RetentionPlanner planner, java.io.PrintWriter out);

    /** 共通の実行基盤の要求を実行し、呼び出し元へ結果を返す。 */
    @Override
    public Integer call() {
      try {
        if (parent.parent.planner == null)
          throw new IllegalStateException("Storage startup gate has not initialized retention");
        execute(parent.parent.planner, spec.commandLine().getOut());
        return 0;
      } catch (RuntimeException error) {
        spec.commandLine()
            .getErr()
            .println("[error] " + dev.mikoto2000.rei.event.FailureMessages.describe(error));
        return 2;
      }
    }
  }

  /** 有界な整理候補を記録します。本文は変更しませんを行うための利用者コマンド。 */
  @Command(name = "plan", description = "有界な整理候補を記録します。本文は変更しません")
  public static final class Plan extends RetentionAction {
    /** 分類。 */
    @Option(names = "--category", defaultValue = "raw-results")
    String category;

    /** プロジェクト。 */
    @Option(names = "--project")
    String project;

    /** 対象データ一覧。 */
    @Option(names = "--max-objects", defaultValue = "100")
    int objects;

    /** バイト数。 */
    @Option(names = "--max-bytes", defaultValue = "16777216")
    long bytes;

    /** 要求された処理を実行する。 */
    @Override
    void execute(dev.mikoto2000.rei.storage.RetentionPlanner planner, java.io.PrintWriter out) {
      var plan = planner.plan(category, selectedScope(project), objects, bytes);
      out.printf(
          "plan=%s scope=%s category=%s snapshot=%s recoverable_bytes=%d scan_limited=%s%n",
          plan.id(),
          plan.scope().isEmpty() ? "global" : plan.scope(),
          category,
          plan.snapshotHash(),
          plan.recoverableBytes(),
          plan.scanLimited());
      for (var candidate : plan.candidates())
        out.printf(
            "candidate %s bytes=%d path=%s reason=retention-policy%n",
            candidate.id(), candidate.size(), candidate.relativePath());
      plan.protectedReasons()
          .forEach((reason, count) -> out.printf("protected %s count=%d%n", reason, count));
      out.println(
          "Approve this snapshot before apply. Quarantine retains the bytes; permanent purge requires separate approval after seven days. Only explicit scope consent enables automatic quarantine.");
    }
  }

  /** このplanの範囲と候補を再確認し、承認を記録しますを行うための利用者コマンド。 */
  @Command(name = "approve", description = "このplanの範囲と候補を再確認し、承認を記録します")
  public static final class Approve extends RetentionAction {
    /** 識別子。 */
    @Parameters(index = "0")
    String id;

    /** 要求された処理を実行する。 */
    @Override
    void execute(dev.mikoto2000.rei.storage.RetentionPlanner planner, java.io.PrintWriter out) {
      var approval = planner.approve(id);
      out.printf("approved plan=%s snapshot=%s%n", approval.planId(), approval.snapshotHash());
    }
  }

  /** 直近100件のplanを表示しますを行うための利用者コマンド。 */
  @Command(name = "history", description = "直近100件のplanを表示します")
  public static final class History extends RetentionAction {
    /** 要求された処理を実行する。 */
    @Override
    void execute(dev.mikoto2000.rei.storage.RetentionPlanner planner, java.io.PrintWriter out) {
      for (var plan : planner.history())
        out.printf(
            "plan=%s scope=%s category=%s snapshot=%s recoverable_bytes=%d created=%s%n",
            plan.id(),
            plan.scope().isEmpty() ? "global" : plan.scope(),
            plan.policyId(),
            plan.snapshotHash(),
            plan.recoverableBytes(),
            plan.created());
      if (parent.parent.executor != null)
        for (var execution : parent.parent.executor.history())
          out.printf(
              "execution=%s plan=%s status=%s updated=%s%n",
              execution.id(), execution.planId(), execution.status(), execution.updated());
    }
  }

  /** 共通の実行基盤に必要な実行操作を扱う。 */
  @Command
  abstract static class ExecutionAction extends RetentionAction {
    /** 識別子。 */
    @Parameters(index = "0")
    String id;

    /** 共通の実行基盤で用いる実行先を返す。 */
    dev.mikoto2000.rei.storage.RetentionExecutor executor() {
      if (parent.parent.executor == null)
        throw new IllegalStateException("Storage execution has not been initialized");
      return parent.parent.executor;
    }

    /** 利用者に結果を伝えるため、実行操作を出力する。 */
    void print(
        dev.mikoto2000.rei.storage.RetentionExecutor.Execution result, java.io.PrintWriter out) {
      out.printf("execution=%s plan=%s status=%s%n", result.id(), result.planId(), result.status());
    }
  }

  /** 承認済みのplanだけを隔離します。再実行で中断処理を再開しますを行うための利用者コマンド。 */
  @Command(name = "apply", description = "承認済みのplanだけを隔離します。再実行で中断処理を再開します")
  public static final class Apply extends ExecutionAction {
    /** 要求された処理を実行する。 */
    @Override
    void execute(dev.mikoto2000.rei.storage.RetentionPlanner planner, java.io.PrintWriter out) {
      print(executor().apply(id), out);
    }
  }

  /** 隔離した実体を復元します。既存ファイルは上書きしませんを行うための利用者コマンド。 */
  @Command(name = "restore", description = "隔離した実体を復元します。既存ファイルは上書きしません")
  public static final class Restore extends ExecutionAction {
    /** 要求された処理を実行する。 */
    @Override
    void execute(dev.mikoto2000.rei.storage.RetentionPlanner planner, java.io.PrintWriter out) {
      print(executor().restore(id), out);
    }
  }

  /** 7日経過した隔離実体の恒久削除を別途承認しますを行うための利用者コマンド。 */
  @Command(name = "approve-purge", description = "7日経過した隔離実体の恒久削除を別途承認します")
  public static final class ApprovePurge extends ExecutionAction {
    /** 要求された処理を実行する。 */
    @Override
    void execute(dev.mikoto2000.rei.storage.RetentionPlanner planner, java.io.PrintWriter out) {
      executor().approvePurge(id);
      out.printf("purge approved execution=%s%n", id);
    }
  }

  /** 別途承認済みの隔離実体を恒久削除しますを行うための利用者コマンド。 */
  @Command(name = "purge", description = "別途承認済みの隔離実体を恒久削除します")
  public static final class Purge extends ExecutionAction {
    /** 要求された処理を実行する。 */
    @Override
    void execute(dev.mikoto2000.rei.storage.RetentionPlanner planner, java.io.PrintWriter out) {
      print(executor().purge(id), out);
    }
  }

  /** 保持条件を変更します。既存planと定期整理の同意は失効しますを行うための利用者コマンド。 */
  @Command(name = "policy", description = "保持条件を変更します。既存planと定期整理の同意は失効します")
  public static final class Policy extends RetentionAction {
    /** 分類。 */
    @Option(names = "--category", required = true)
    String category;

    /** 日数。 */
    @Option(names = "--retention-days")
    String days;

    /** 件数。 */
    @Option(names = "--max-count")
    Long count;

    /** バイト数。 */
    @Option(names = "--max-policy-bytes")
    Long bytes;

    /** 保持内容の消去件数。 */
    @Option(names = "--clear-count")
    boolean clearCount;

    /** 保持内容の消去バイト数。 */
    @Option(names = "--clear-policy-bytes")
    boolean clearBytes;

    /** 要求された処理を実行する。 */
    @Override
    void execute(dev.mikoto2000.rei.storage.RetentionPlanner planner, java.io.PrintWriter out) {
      var old = planner.policy(category);
      if (clearCount && count != null || clearBytes && bytes != null)
        throw new IllegalArgumentException("Conflicting limit options");
      java.time.Duration retention =
          days == null
              ? (old.retentionSeconds() == null
                  ? null
                  : java.time.Duration.ofSeconds(old.retentionSeconds()))
              : days.equals("none") ? null : java.time.Duration.ofDays(Long.parseLong(days));
      planner.updatePolicy(
          category,
          retention,
          clearBytes ? null : bytes == null ? old.maxBytes() : bytes,
          clearCount ? null : count == null ? old.maxCount() : count);
      var policy = planner.policy(category);
      out.printf(
          "policy=%s version=%d retention_seconds=%s max_count=%s max_bytes=%s automatic=false%n",
          category,
          policy.version(),
          policy.retentionSeconds(),
          policy.maxCount(),
          policy.maxBytes());
    }
  }

  /** 条件・範囲・最大量・頻度への明示同意で隔離だけを有効化しますを行うための利用者コマンド。 */
  @Command(
      name = "auto",
      subcommands = {AutoPropose.class, AutoApprove.class, AutoDisable.class, AutoHistory.class},
      description = "条件・範囲・最大量・頻度への明示同意で隔離だけを有効化します")
  public static final class Auto {
    /** 親。 */
    @ParentCommand Retention parent;
  }

  /** 共通の実行基盤に必要な自動操作を扱う。 */
  @Command
  abstract static class AutoAction implements java.util.concurrent.Callable<Integer> {
    /** 親。 */
    @ParentCommand Auto parent;

    /** 仕様。 */
    @Spec picocli.CommandLine.Model.CommandSpec spec;

    /** 要求された処理を実行する。 */
    abstract void execute(
        dev.mikoto2000.rei.storage.AutomaticRetention automatic, java.io.PrintWriter out);

    /** 共通の実行基盤の要求を実行し、呼び出し元へ結果を返す。 */
    @Override
    public Integer call() {
      try {
        var automatic = parent.parent.parent.automatic;
        if (automatic == null)
          throw new IllegalStateException("Automatic retention has not initialized");
        execute(automatic, spec.commandLine().getOut());
        return 0;
      } catch (RuntimeException error) {
        spec.commandLine()
            .getErr()
            .println("[error] " + dev.mikoto2000.rei.event.FailureMessages.describe(error));
        return 2;
      }
    }

    /** 利用者に結果を伝えるため、自動操作を出力する。 */
    void print(
        dev.mikoto2000.rei.storage.AutomaticRetention.Consent consent, java.io.PrintWriter out) {
      out.printf(
          "consent=%s category=%s scope=%s snapshot=%s policy_version=%d retention_seconds=%s max_policy_bytes=%s max_count=%s max_objects=%d max_bytes=%d interval_seconds=%d permanent_purge=false%n",
          consent.id(),
          consent.policy().id(),
          consent.scope().isEmpty() ? "global" : consent.scope(),
          consent.snapshotHash(),
          consent.policy().version(),
          consent.policy().retentionSeconds(),
          consent.policy().maxBytes(),
          consent.policy().maxCount(),
          consent.maxObjects(),
          consent.maxBytes(),
          consent.intervalSeconds());
    }
  }

  /** 定期隔離の具体的な同意内容を作成・表示します。まだ実行しませんを行うための利用者コマンド。 */
  @Command(name = "propose", description = "定期隔離の具体的な同意内容を作成・表示します。まだ実行しません")
  public static final class AutoPropose extends AutoAction {
    /** 分類。 */
    @Option(names = "--category", required = true)
    String category;

    /** プロジェクト。 */
    @Option(names = "--project")
    String project;

    /** 対象データ一覧。 */
    @Option(names = "--max-objects", required = true)
    int objects;

    /** バイト数。 */
    @Option(names = "--max-bytes", required = true)
    long bytes;

    /** 秒数。 */
    @Option(names = "--interval-seconds", required = true)
    long seconds;

    /** 要求された処理を実行する。 */
    @Override
    void execute(dev.mikoto2000.rei.storage.AutomaticRetention automatic, java.io.PrintWriter out) {
      print(
          automatic.propose(
              category,
              selectedScope(project),
              objects,
              bytes,
              java.time.Duration.ofSeconds(seconds)),
          out);
    }
  }

  /** 表示済みの条件と範囲へ同意し、定期隔離を有効化しますを行うための利用者コマンド。 */
  @Command(name = "approve", description = "表示済みの条件と範囲へ同意し、定期隔離を有効化します")
  public static final class AutoApprove extends AutoAction {
    /** 識別子。 */
    @Parameters(index = "0")
    String id;

    /** 要求された処理を実行する。 */
    @Override
    void execute(dev.mikoto2000.rei.storage.AutomaticRetention automatic, java.io.PrintWriter out) {
      automatic.approve(id);
      out.printf("automatic quarantine enabled consent=%s; permanent purge remains manual%n", id);
    }
  }

  /** 同意した範囲の定期隔離を停止しますを行うための利用者コマンド。 */
  @Command(name = "disable", description = "同意した範囲の定期隔離を停止します")
  public static final class AutoDisable extends AutoAction {
    /** 識別子。 */
    @Parameters(index = "0")
    String id;

    /** 要求された処理を実行する。 */
    @Override
    void execute(dev.mikoto2000.rei.storage.AutomaticRetention automatic, java.io.PrintWriter out) {
      automatic.disable(id);
      out.printf("automatic quarantine disabled consent=%s%n", id);
    }
  }

  /** 最新100件の同意内容と状態を表示しますを行うための利用者コマンド。 */
  @Command(name = "history", description = "最新100件の同意内容と状態を表示します")
  public static final class AutoHistory extends AutoAction {
    /** 要求された処理を実行する。 */
    @Override
    void execute(dev.mikoto2000.rei.storage.AutomaticRetention automatic, java.io.PrintWriter out) {
      for (var consent : automatic.history()) {
        print(consent, out);
        out.printf("state=%s%n", automatic.state(consent.id()));
      }
    }
  }

  /** storage.dbの観測と明示的な容量回収を行うための利用者コマンド。 */
  @Command(
      name = "maintenance",
      subcommands = {
        MaintenanceStatus.class,
        Checkpoint.class,
        IncrementalVacuum.class,
        Vacuum.class
      },
      description = "storage.dbの観測と明示的な容量回収")
  public static final class Maintenance {
    /** 親。 */
    @ParentCommand StorageCommand parent;
  }

  /** 共通の実行基盤に必要な保守操作を扱う。 */
  @Command
  abstract static class MaintenanceAction implements java.util.concurrent.Callable<Integer> {
    /** 親。 */
    @ParentCommand Maintenance parent;

    /** 仕様。 */
    @Spec picocli.CommandLine.Model.CommandSpec spec;

    /** 要求された処理を実行する。 */
    abstract void execute(
        dev.mikoto2000.rei.storage.StorageMaintenance maintenance, java.io.PrintWriter out);

    /** 共通の実行基盤の要求を実行し、呼び出し元へ結果を返す。 */
    @Override
    public Integer call() {
      try {
        if (parent.parent.maintenance == null)
          throw new IllegalStateException("Storage maintenance has not initialized");
        execute(parent.parent.maintenance, spec.commandLine().getOut());
        return 0;
      } catch (RuntimeException error) {
        spec.commandLine()
            .getErr()
            .println("[error] " + dev.mikoto2000.rei.event.FailureMessages.describe(error));
        return 2;
      }
    }
  }

  /** DB・WAL・空きページ・ディスク残量を読み取り専用で観測しますを行うための利用者コマンド。 */
  @Command(name = "status", description = "DB・WAL・空きページ・ディスク残量を読み取り専用で観測します")
  public static final class MaintenanceStatus extends MaintenanceAction {
    /** 要求された処理を実行する。 */
    @Override
    void execute(
        dev.mikoto2000.rei.storage.StorageMaintenance maintenance, java.io.PrintWriter out) {
      var status = maintenance.status();
      out.printf(
          "database_bytes=%d wal_bytes=%d temporary_bytes=%d page_size=%d page_count=%d free_pages=%d used_page_bytes=%d auto_vacuum=%d usable_disk_bytes=%d%n",
          status.databaseBytes(),
          status.walBytes(),
          status.temporaryBytes(),
          status.pageSize(),
          status.pageCount(),
          status.freePages(),
          status.estimatedUsedPageBytes(),
          status.autoVacuum(),
          status.usableDiskBytes());
    }
  }

  /** PASSIVE checkpointを試し、Readerによる未回収分を報告しますを行うための利用者コマンド。 */
  @Command(name = "checkpoint", description = "PASSIVE checkpointを試し、Readerによる未回収分を報告します")
  public static final class Checkpoint extends MaintenanceAction {
    /** 要求された処理を実行する。 */
    @Override
    void execute(
        dev.mikoto2000.rei.storage.StorageMaintenance maintenance, java.io.PrintWriter out) {
      var status = maintenance.checkpoint();
      out.printf(
          "busy=%d log_frames=%d checkpointed_frames=%d%n",
          status.busy(), status.logFrames(), status.checkpointedFrames());
    }
  }

  /** 既存のauto_vacuumが2の場合だけ有界に空きページを回収しますを行うための利用者コマンド。 */
  @Command(name = "incremental-vacuum", description = "既存のauto_vacuumが2の場合だけ有界に空きページを回収します")
  public static final class IncrementalVacuum extends MaintenanceAction {
    /** ページ一覧。 */
    @Option(names = "--pages", required = true)
    int pages;

    /** 要求された処理を実行する。 */
    @Override
    void execute(
        dev.mikoto2000.rei.storage.StorageMaintenance maintenance, java.io.PrintWriter out) {
      out.printf("incremental_vacuum_supported=%s%n", maintenance.incrementalVacuum(pages));
    }
  }

  /** 明示した時間枠内でVACUUMを実行しますを行うための利用者コマンド。 */
  @Command(name = "vacuum", description = "明示した時間枠内でVACUUMを実行します")
  public static final class Vacuum extends MaintenanceAction {
    /** 秒数。 */
    @Option(names = "--window-seconds", required = true)
    long seconds;

    /** 要求された処理を実行する。 */
    @Override
    void execute(
        dev.mikoto2000.rei.storage.StorageMaintenance maintenance, java.io.PrintWriter out) {
      maintenance.vacuum(java.time.Duration.ofSeconds(seconds));
      out.println("VACUUM completed");
    }
  }

  /** 保有情報。 */
  private final StorageInventory inventory =
      new StorageInventory(Clock.systemUTC(), 20_000, 16L * 1024 * 1024);

  /** 件数・容量・前回との差分を表示しますを行うための利用者コマンド。 */
  @Command(name = "status", description = "件数・容量・前回との差分を表示します")
  public static final class Status implements java.util.concurrent.Callable<Integer> {
    /** 親。 */
    @ParentCommand StorageCommand parent;

    /** プロジェクト。 */
    @Option(names = "--project", description = "current: 現在のプロジェクトのみ")
    String project;

    /** 仕様。 */
    @Spec picocli.CommandLine.Model.CommandSpec spec;

    /** 件数・容量・前回との差分を表示しますを行う。 */
    @Override
    public Integer call() {
      if (project != null && !project.equals("current")) {
        spec.commandLine().getErr().println("Use /storage status [--project current]");
        return 2;
      }
      var context = dev.mikoto2000.rei.core.project.ProjectService.contextForOperation();
      if (project != null && context == null) {
        spec.commandLine()
            .getErr()
            .println("No current project; select a project before measuring its storage.");
        return 2;
      }
      var root = parent.planner == null ? ReiDataDirectory.current() : parent.planner.root();
      var report =
          parent.inventory.measure(
              project == null ? root : root.resolve("projects").resolve(context.id()));
      var out = spec.commandLine().getOut();
      out.println("storage: " + report.root());
      out.println("category files records physical_bytes(file length) logical_bytes");
      report.categories().entrySet().stream()
          .sorted(java.util.Map.Entry.comparingByKey())
          .forEach(
              entry -> {
                var row = entry.getValue();
                out.printf(
                    "%s %d %s %d unknown%n",
                    entry.getKey(),
                    row.files(),
                    row.records() == null ? "unknown" : row.records(),
                    row.physicalBytes());
              });
      out.printf(
          "delta_bytes=%s bytes_per_second=%s complete=%s%n",
          report.deltaBytes(), report.bytesPerSecond(), report.complete());
      report.unknown().forEach(reason -> out.println("unknown: " + reason));
      if (parent.planner != null)
        try {
          var managed =
              project == null ? parent.planner.statusAll() : parent.planner.status(context.id());
          out.println(
              "managed metadata scope="
                  + (project == null ? "all" : context.id())
                  + "; counts include audit tombstones; body bytes include quarantine and exclude deleted bodies, not a fresh content verification");
          managed
              .objects()
              .forEach(
                  row ->
                      out.printf(
                          "managed %s count=%d recorded_body_bytes=%d%n",
                          row.kind(), row.count(), row.recordedBodyBytes()));
          managed
              .protectionReasons()
              .forEach((reason, count) -> out.printf("protected %s count=%d%n", reason, count));
          out.printf(
              "reference_scan_limited=%s; run retention plan for bounded, verified candidates%n",
              managed.referenceScanLimited());
        } catch (RuntimeException error) {
          out.println(
              "unknown: managed metadata could not be read: "
                  + dev.mikoto2000.rei.event.FailureMessages.describe(error));
        }
      if (parent.maintenance != null)
        try {
          var database = parent.maintenance.status();
          out.printf(
              "storage.db physical_bytes=%d wal_bytes=%d free_pages=%d estimated_used_page_bytes=%d temporary_bytes=%d usable_disk_bytes=%d%n",
              database.databaseBytes(),
              database.walBytes(),
              database.freePages(),
              database.estimatedUsedPageBytes(),
              database.temporaryBytes(),
              database.usableDiskBytes());
        } catch (RuntimeException error) {
          out.println(
              "unknown: database page statistics could not be read: "
                  + dev.mikoto2000.rei.event.FailureMessages.describe(error));
        }
      out.println(
          "Custom paths and explicit exports outside this root are unscanned. Measurements may race with writers.");
      return 0;
    }
  }
}
