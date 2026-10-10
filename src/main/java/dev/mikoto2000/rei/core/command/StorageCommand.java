package dev.mikoto2000.rei.core.command;

import dev.mikoto2000.rei.storage.StorageInventory;
import dev.mikoto2000.rei.core.datasource.ReiDataDirectory;
import dev.mikoto2000.rei.core.project.ProjectStorage;
import java.time.Clock;
import picocli.CommandLine.*;
import org.springframework.stereotype.Component;

@Component
@Command(name="storage", description="保存量を読み取り専用で観測します", subcommands=StorageCommand.Status.class)
public final class StorageCommand {
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
      var report = parent.inventory.measure(project == null ? ReiDataDirectory.current() : ProjectStorage.directory(context.id()));
      var out = spec.commandLine().getOut();
      out.println("storage: " + report.root());
      out.println("category files records physical_bytes(file length) logical_bytes");
      report.categories().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey()).forEach(entry -> {
        var row = entry.getValue();
        out.printf("%s %d %s %d unknown%n", entry.getKey(), row.files(), row.records() == null ? "unknown" : row.records(), row.physicalBytes());
      });
      out.printf("delta_bytes=%s bytes_per_second=%s complete=%s%n", report.deltaBytes(), report.bytesPerSecond(), report.complete());
      report.unknown().forEach(reason -> out.println("unknown: " + reason));
      out.println("Custom paths and explicit exports outside this root are unscanned. Measurements may race with writers.");
      return 0;
    }
  }
}
