package dev.mikoto2000.rei.core.command;

import dev.mikoto2000.rei.storage.StorageInventory;
import dev.mikoto2000.rei.core.datasource.ReiDataDirectory;
import dev.mikoto2000.rei.core.project.ProjectStorage;
import java.time.Clock;
import picocli.CommandLine.*;
import org.springframework.stereotype.Component;

@Component
@Command(name="storage", description="保存量と整理候補を管理します", subcommands={StorageCommand.Status.class,StorageCommand.Retention.class,StorageCommand.Maintenance.class})
public final class StorageCommand {
  private dev.mikoto2000.rei.storage.RetentionPlanner planner;
  private dev.mikoto2000.rei.storage.RetentionExecutor executor;
  private dev.mikoto2000.rei.storage.StorageMaintenance maintenance;
  private dev.mikoto2000.rei.storage.AutomaticRetention automatic;
  public StorageCommand(){this(null);}
  @org.springframework.beans.factory.annotation.Autowired
  public StorageCommand(dev.mikoto2000.rei.storage.RetentionPlanner planner){this.planner=planner;}
  public StorageCommand(dev.mikoto2000.rei.storage.RetentionPlanner planner,dev.mikoto2000.rei.storage.RetentionExecutor executor,dev.mikoto2000.rei.storage.StorageMaintenance maintenance,dev.mikoto2000.rei.storage.AutomaticRetention automatic){this(planner);setExecutionServices(executor,maintenance,automatic);}
  @org.springframework.beans.factory.annotation.Autowired(required=false)
  public void setExecutionServices(dev.mikoto2000.rei.storage.RetentionExecutor executor,dev.mikoto2000.rei.storage.StorageMaintenance maintenance,dev.mikoto2000.rei.storage.AutomaticRetention automatic){this.executor=executor;this.maintenance=maintenance;this.automatic=automatic;}
  @Command(name="retention",subcommands={Plan.class,Approve.class,History.class,Apply.class,Restore.class,ApprovePurge.class,Purge.class,Policy.class,Auto.class},description="dry-run、明示承認、隔離・復元。定期整理は既定で無効です")
  public static final class Retention { @ParentCommand StorageCommand parent; }
  static String selectedScope(String project) {
    if(project==null)return null;if(!project.equals("current"))throw new IllegalArgumentException("Use --project current or omit it for global scope");
    var context=dev.mikoto2000.rei.core.project.ProjectService.contextForOperation();
    if(context==null)throw new IllegalArgumentException("No current project; select a project before planning");return context.id();
  }
  @Command
  abstract static class RetentionAction implements java.util.concurrent.Callable<Integer> {
    @ParentCommand Retention parent;@Spec picocli.CommandLine.Model.CommandSpec spec;
    abstract void execute(dev.mikoto2000.rei.storage.RetentionPlanner planner,java.io.PrintWriter out);
    @Override public Integer call(){try{if(parent.parent.planner==null)throw new IllegalStateException("Storage startup gate has not initialized retention");execute(parent.parent.planner,spec.commandLine().getOut());return 0;}catch(RuntimeException error){spec.commandLine().getErr().println("[error] "+dev.mikoto2000.rei.event.CredentialRedactor.redact(error.getMessage()));return 2;}}
  }
  @Command(name="plan",description="有界な整理候補を記録します。本文は変更しません")
  public static final class Plan extends RetentionAction {
    @Option(names="--category",defaultValue="raw-results") String category;
    @Option(names="--project") String project;
    @Option(names="--max-objects",defaultValue="100") int objects;
    @Option(names="--max-bytes",defaultValue="16777216") long bytes;
    @Override void execute(dev.mikoto2000.rei.storage.RetentionPlanner planner,java.io.PrintWriter out){var plan=planner.plan(category,selectedScope(project),objects,bytes);out.printf("plan=%s scope=%s category=%s snapshot=%s recoverable_bytes=%d scan_limited=%s%n",plan.id(),plan.scope().isEmpty()?"global":plan.scope(),category,plan.snapshotHash(),plan.recoverableBytes(),plan.scanLimited());for(var candidate:plan.candidates())out.printf("candidate %s bytes=%d path=%s reason=retention-policy%n",candidate.id(),candidate.size(),candidate.relativePath());plan.protectedReasons().forEach((reason,count)->out.printf("protected %s count=%d%n",reason,count));out.println("Approve this snapshot before apply. Quarantine retains the bytes; permanent purge requires separate approval after seven days. Only explicit scope consent enables automatic quarantine.");}
  }
  @Command(name="approve",description="このplanの範囲と候補を再確認し、承認を記録します")
  public static final class Approve extends RetentionAction {
    @Parameters(index="0") String id;
    @Override void execute(dev.mikoto2000.rei.storage.RetentionPlanner planner,java.io.PrintWriter out){var approval=planner.approve(id);out.printf("approved plan=%s snapshot=%s%n",approval.planId(),approval.snapshotHash());}
  }
  @Command(name="history",description="直近100件のplanを表示します")
  public static final class History extends RetentionAction {
    @Override void execute(dev.mikoto2000.rei.storage.RetentionPlanner planner,java.io.PrintWriter out){for(var plan:planner.history())out.printf("plan=%s scope=%s category=%s snapshot=%s recoverable_bytes=%d created=%s%n",plan.id(),plan.scope().isEmpty()?"global":plan.scope(),plan.policyId(),plan.snapshotHash(),plan.recoverableBytes(),plan.created());if(parent.parent.executor!=null)for(var execution:parent.parent.executor.history())out.printf("execution=%s plan=%s status=%s updated=%s%n",execution.id(),execution.planId(),execution.status(),execution.updated());}
  }
  @Command abstract static class ExecutionAction extends RetentionAction {
    @Parameters(index="0") String id;
    dev.mikoto2000.rei.storage.RetentionExecutor executor(){if(parent.parent.executor==null)throw new IllegalStateException("Storage execution has not been initialized");return parent.parent.executor;}
    void print(dev.mikoto2000.rei.storage.RetentionExecutor.Execution result,java.io.PrintWriter out){out.printf("execution=%s plan=%s status=%s%n",result.id(),result.planId(),result.status());}
  }
  @Command(name="apply",description="承認済みのplanだけを隔離します。再実行で中断処理を再開します")
  public static final class Apply extends ExecutionAction {@Override void execute(dev.mikoto2000.rei.storage.RetentionPlanner planner,java.io.PrintWriter out){print(executor().apply(id),out);}}
  @Command(name="restore",description="隔離した実体を復元します。既存ファイルは上書きしません")
  public static final class Restore extends ExecutionAction {@Override void execute(dev.mikoto2000.rei.storage.RetentionPlanner planner,java.io.PrintWriter out){print(executor().restore(id),out);}}
  @Command(name="approve-purge",description="7日経過した隔離実体の恒久削除を別途承認します")
  public static final class ApprovePurge extends ExecutionAction {@Override void execute(dev.mikoto2000.rei.storage.RetentionPlanner planner,java.io.PrintWriter out){executor().approvePurge(id);out.printf("purge approved execution=%s%n",id);}}
  @Command(name="purge",description="別途承認済みの隔離実体を恒久削除します")
  public static final class Purge extends ExecutionAction {@Override void execute(dev.mikoto2000.rei.storage.RetentionPlanner planner,java.io.PrintWriter out){print(executor().purge(id),out);}}
  @Command(name="policy",description="保持条件を変更します。既存planと定期整理の同意は失効します")
  public static final class Policy extends RetentionAction {
    @Option(names="--category",required=true) String category;
    @Option(names="--retention-days") String days;
    @Option(names="--max-count") Long count;
    @Option(names="--max-policy-bytes") Long bytes;
    @Option(names="--clear-count") boolean clearCount;
    @Option(names="--clear-policy-bytes") boolean clearBytes;
    @Override void execute(dev.mikoto2000.rei.storage.RetentionPlanner planner,java.io.PrintWriter out){var old=planner.policy(category);if(clearCount&&count!=null||clearBytes&&bytes!=null)throw new IllegalArgumentException("Conflicting limit options");java.time.Duration retention=days==null?(old.retentionSeconds()==null?null:java.time.Duration.ofSeconds(old.retentionSeconds())):days.equals("none")?null:java.time.Duration.ofDays(Long.parseLong(days));planner.updatePolicy(category,retention,clearBytes?null:bytes==null?old.maxBytes():bytes,clearCount?null:count==null?old.maxCount():count);var policy=planner.policy(category);out.printf("policy=%s version=%d retention_seconds=%s max_count=%s max_bytes=%s automatic=false%n",category,policy.version(),policy.retentionSeconds(),policy.maxCount(),policy.maxBytes());}
  }
  @Command(name="auto",subcommands={AutoPropose.class,AutoApprove.class,AutoDisable.class,AutoHistory.class},description="条件・範囲・最大量・頻度への明示同意で隔離だけを有効化します")
  public static final class Auto {@ParentCommand Retention parent;}
  @Command abstract static class AutoAction implements java.util.concurrent.Callable<Integer> {
    @ParentCommand Auto parent;@Spec picocli.CommandLine.Model.CommandSpec spec;
    abstract void execute(dev.mikoto2000.rei.storage.AutomaticRetention automatic,java.io.PrintWriter out);
    @Override public Integer call(){try{var automatic=parent.parent.parent.automatic;if(automatic==null)throw new IllegalStateException("Automatic retention has not initialized");execute(automatic,spec.commandLine().getOut());return 0;}catch(RuntimeException error){spec.commandLine().getErr().println("[error] "+dev.mikoto2000.rei.event.CredentialRedactor.redact(error.getMessage()));return 2;}}
    void print(dev.mikoto2000.rei.storage.AutomaticRetention.Consent consent,java.io.PrintWriter out){out.printf("consent=%s category=%s scope=%s snapshot=%s policy_version=%d retention_seconds=%s max_policy_bytes=%s max_count=%s max_objects=%d max_bytes=%d interval_seconds=%d permanent_purge=false%n",consent.id(),consent.policy().id(),consent.scope().isEmpty()?"global":consent.scope(),consent.snapshotHash(),consent.policy().version(),consent.policy().retentionSeconds(),consent.policy().maxBytes(),consent.policy().maxCount(),consent.maxObjects(),consent.maxBytes(),consent.intervalSeconds());}
  }
  @Command(name="propose",description="定期隔離の具体的な同意内容を作成・表示します。まだ実行しません")
  public static final class AutoPropose extends AutoAction {
    @Option(names="--category",required=true) String category;@Option(names="--project") String project;
    @Option(names="--max-objects",required=true) int objects;@Option(names="--max-bytes",required=true) long bytes;@Option(names="--interval-seconds",required=true) long seconds;
    @Override void execute(dev.mikoto2000.rei.storage.AutomaticRetention automatic,java.io.PrintWriter out){print(automatic.propose(category,selectedScope(project),objects,bytes,java.time.Duration.ofSeconds(seconds)),out);}
  }
  @Command(name="approve",description="表示済みの条件と範囲へ同意し、定期隔離を有効化します")
  public static final class AutoApprove extends AutoAction {@Parameters(index="0") String id;@Override void execute(dev.mikoto2000.rei.storage.AutomaticRetention automatic,java.io.PrintWriter out){automatic.approve(id);out.printf("automatic quarantine enabled consent=%s; permanent purge remains manual%n",id);}}
  @Command(name="disable",description="同意した範囲の定期隔離を停止します")
  public static final class AutoDisable extends AutoAction {@Parameters(index="0") String id;@Override void execute(dev.mikoto2000.rei.storage.AutomaticRetention automatic,java.io.PrintWriter out){automatic.disable(id);out.printf("automatic quarantine disabled consent=%s%n",id);}}
  @Command(name="history",description="最新100件の同意内容と状態を表示します")
  public static final class AutoHistory extends AutoAction {@Override void execute(dev.mikoto2000.rei.storage.AutomaticRetention automatic,java.io.PrintWriter out){for(var consent:automatic.history()){print(consent,out);out.printf("state=%s%n",automatic.state(consent.id()));}}}
  @Command(name="maintenance",subcommands={MaintenanceStatus.class,Checkpoint.class,IncrementalVacuum.class,Vacuum.class},description="storage.dbの観測と明示的な容量回収")
  public static final class Maintenance {@ParentCommand StorageCommand parent;}
  @Command abstract static class MaintenanceAction implements java.util.concurrent.Callable<Integer> {
    @ParentCommand Maintenance parent;@Spec picocli.CommandLine.Model.CommandSpec spec;
    abstract void execute(dev.mikoto2000.rei.storage.StorageMaintenance maintenance,java.io.PrintWriter out);
    @Override public Integer call(){try{if(parent.parent.maintenance==null)throw new IllegalStateException("Storage maintenance has not initialized");execute(parent.parent.maintenance,spec.commandLine().getOut());return 0;}catch(RuntimeException error){spec.commandLine().getErr().println("[error] "+dev.mikoto2000.rei.event.CredentialRedactor.redact(error.getMessage()));return 2;}}
  }
  @Command(name="status",description="DB・WAL・空きページ・ディスク残量を読み取り専用で観測します")
  public static final class MaintenanceStatus extends MaintenanceAction {@Override void execute(dev.mikoto2000.rei.storage.StorageMaintenance maintenance,java.io.PrintWriter out){var status=maintenance.status();out.printf("database_bytes=%d wal_bytes=%d temporary_bytes=%d page_size=%d page_count=%d free_pages=%d used_page_bytes=%d auto_vacuum=%d usable_disk_bytes=%d%n",status.databaseBytes(),status.walBytes(),status.temporaryBytes(),status.pageSize(),status.pageCount(),status.freePages(),status.estimatedUsedPageBytes(),status.autoVacuum(),status.usableDiskBytes());}}
  @Command(name="checkpoint",description="PASSIVE checkpointを試し、Readerによる未回収分を報告します")
  public static final class Checkpoint extends MaintenanceAction {@Override void execute(dev.mikoto2000.rei.storage.StorageMaintenance maintenance,java.io.PrintWriter out){var status=maintenance.checkpoint();out.printf("busy=%d log_frames=%d checkpointed_frames=%d%n",status.busy(),status.logFrames(),status.checkpointedFrames());}}
  @Command(name="incremental-vacuum",description="既存のauto_vacuumが2の場合だけ有界に空きページを回収します")
  public static final class IncrementalVacuum extends MaintenanceAction {@Option(names="--pages",required=true) int pages;@Override void execute(dev.mikoto2000.rei.storage.StorageMaintenance maintenance,java.io.PrintWriter out){out.printf("incremental_vacuum_supported=%s%n",maintenance.incrementalVacuum(pages));}}
  @Command(name="vacuum",description="明示した時間枠内でVACUUMを実行します")
  public static final class Vacuum extends MaintenanceAction {@Option(names="--window-seconds",required=true) long seconds;@Override void execute(dev.mikoto2000.rei.storage.StorageMaintenance maintenance,java.io.PrintWriter out){maintenance.vacuum(java.time.Duration.ofSeconds(seconds));out.println("VACUUM completed");}}
  private final StorageInventory inventory = new StorageInventory(Clock.systemUTC(), 20_000, 16L * 1024 * 1024);
  @Command(name="status", description="件数・容量・前回との差分を表示します")
  public static final class Status implements java.util.concurrent.Callable<Integer> {
    @ParentCommand StorageCommand parent;
    @Option(names="--project", description="current: 現在のプロジェクトのみ") String project;
    @Spec picocli.CommandLine.Model.CommandSpec spec;
    @Override public Integer call() {
      if (project != null && !project.equals("current")) {
        spec.commandLine().getErr().println("Use /storage status [--project current]"); return 2;
      }
      var context = dev.mikoto2000.rei.core.project.ProjectService.contextForOperation();
      if (project != null && context == null) {
        spec.commandLine().getErr().println("No current project; select a project before measuring its storage.");
        return 2;
      }
      var root=parent.planner==null?ReiDataDirectory.current():parent.planner.root();
      var report = parent.inventory.measure(project == null ? root : root.resolve("projects").resolve(context.id()));
      var out = spec.commandLine().getOut();
      out.println("storage: " + report.root());
      out.println("category files records physical_bytes(file length) logical_bytes");
      report.categories().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey()).forEach(entry -> {
        var row = entry.getValue();
        out.printf("%s %d %s %d unknown%n", entry.getKey(), row.files(), row.records() == null ? "unknown" : row.records(), row.physicalBytes());
      });
      out.printf("delta_bytes=%s bytes_per_second=%s complete=%s%n", report.deltaBytes(), report.bytesPerSecond(), report.complete());
      report.unknown().forEach(reason -> out.println("unknown: " + reason));
      if(parent.planner!=null)try {
        var managed=project==null?parent.planner.statusAll():parent.planner.status(context.id());
        out.println("managed metadata scope="+(project==null?"all":context.id())+"; counts include audit tombstones; body bytes include quarantine and exclude deleted bodies, not a fresh content verification");
        managed.objects().forEach(row->out.printf("managed %s count=%d recorded_body_bytes=%d%n",row.kind(),row.count(),row.recordedBodyBytes()));
        managed.protectionReasons().forEach((reason,count)->out.printf("protected %s count=%d%n",reason,count));
        out.printf("reference_scan_limited=%s; run retention plan for bounded, verified candidates%n",managed.referenceScanLimited());
      }catch(RuntimeException error){out.println("unknown: managed metadata could not be read");}
      if(parent.maintenance!=null)try{var database=parent.maintenance.status();out.printf("storage.db physical_bytes=%d wal_bytes=%d free_pages=%d estimated_used_page_bytes=%d temporary_bytes=%d usable_disk_bytes=%d%n",database.databaseBytes(),database.walBytes(),database.freePages(),database.estimatedUsedPageBytes(),database.temporaryBytes(),database.usableDiskBytes());}catch(RuntimeException error){out.println("unknown: database page statistics could not be read");}
      out.println("Custom paths and explicit exports outside this root are unscanned. Measurements may race with writers.");
      return 0;
    }
  }
}
