package dev.mikoto2000.rei.core.command;

import dev.mikoto2000.rei.storage.StorageInventory;
import dev.mikoto2000.rei.core.datasource.ReiDataDirectory;
import dev.mikoto2000.rei.core.project.ProjectStorage;
import java.time.Clock;
import picocli.CommandLine.*;
import org.springframework.stereotype.Component;

@Component
@Command(name="storage", description="保存量と整理候補を管理します", subcommands={StorageCommand.Status.class,StorageCommand.Retention.class})
public final class StorageCommand {
  private dev.mikoto2000.rei.storage.RetentionPlanner planner;
  public StorageCommand(){this(null);}
  @org.springframework.beans.factory.annotation.Autowired
  public StorageCommand(dev.mikoto2000.rei.storage.RetentionPlanner planner){this.planner=planner;}
  @Command(name="retention",subcommands={Plan.class,Approve.class,History.class},description="dry-runと明示承認。自動削除は既定で無効です")
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
    @Override void execute(dev.mikoto2000.rei.storage.RetentionPlanner planner,java.io.PrintWriter out){var plan=planner.plan(category,selectedScope(project),objects,bytes);out.printf("plan=%s scope=%s category=%s snapshot=%s recoverable_bytes=%d scan_limited=%s%n",plan.id(),plan.scope().isEmpty()?"global":plan.scope(),category,plan.snapshotHash(),plan.recoverableBytes(),plan.scanLimited());for(var candidate:plan.candidates())out.printf("candidate %s bytes=%d path=%s reason=retention-policy%n",candidate.id(),candidate.size(),candidate.relativePath());plan.protectedReasons().forEach((reason,count)->out.printf("protected %s count=%d%n",reason,count));out.println("Automatic retention is disabled. Approval records this exact snapshot; physical execution is introduced in Phase 6.");}
  }
  @Command(name="approve",description="このplanの範囲と候補を再確認し、承認を記録します")
  public static final class Approve extends RetentionAction {
    @Parameters(index="0") String id;
    @Override void execute(dev.mikoto2000.rei.storage.RetentionPlanner planner,java.io.PrintWriter out){var approval=planner.approve(id);out.printf("approved plan=%s snapshot=%s%n",approval.planId(),approval.snapshotHash());}
  }
  @Command(name="history",description="直近100件のplanを表示します")
  public static final class History extends RetentionAction {
    @Override void execute(dev.mikoto2000.rei.storage.RetentionPlanner planner,java.io.PrintWriter out){for(var plan:planner.history())out.printf("plan=%s scope=%s category=%s snapshot=%s recoverable_bytes=%d created=%s%n",plan.id(),plan.scope().isEmpty()?"global":plan.scope(),plan.policyId(),plan.snapshotHash(),plan.recoverableBytes(),plan.created());}
  }
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
        out.println("managed metadata scope="+(project==null?"all":context.id())+"; body bytes are recorded sizes, not a fresh content verification");
        managed.objects().forEach(row->out.printf("managed %s count=%d recorded_body_bytes=%d%n",row.kind(),row.count(),row.recordedBodyBytes()));
        managed.protectionReasons().forEach((reason,count)->out.printf("protected %s count=%d%n",reason,count));
        out.printf("reference_scan_limited=%s; run retention plan for bounded, verified candidates%n",managed.referenceScanLimited());
      }catch(RuntimeException error){out.println("unknown: managed metadata could not be read");}
      out.println("Custom paths and explicit exports outside this root are unscanned. Measurements may race with writers.");
      return 0;
    }
  }
}
