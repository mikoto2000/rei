package dev.mikoto2000.rei.checkpoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

/** 再開用の記録再開Smokeの結果と状態の契約を検証するテスト。 */
@Tag("integration")
class CheckpointRestartSmokeTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** 作業の中断と再開で、保存した情報を、再起動や復元後の処理へ正しく引き継ぐことを確認する。 */
  @Test
  void independentJvmRestartThenStateChangeReconciliationAndResume() throws Exception {
    Files.writeString(root.resolve("artifact.txt"), "before");
    run("write");
    Files.writeString(root.resolve("artifact.txt"), "changed after crash");
    run("resume");
  }

  /** 検証用に、作業の中断と再開の操作を実行する。 */
  void run(String mode) throws Exception {
    var builder =
        new ProcessBuilder(
                dev.mikoto2000.rei.testsupport.JavaFixtureCommand.command(
                    root, Probe.class, root.toString(), mode))
            .redirectErrorStream(true)
            .redirectOutput(root.resolve(mode + ".log").toFile());
    var process = builder.start();
    assertTrue(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS));
    assertEquals(0, process.exitValue(), Files.readString(root.resolve(mode + ".log")));
  }

  /** 検証条件を再現するための利用可否の確認。 */
  public static class Probe {
    /** 検証用に、作業の中断と再開の起動要求を受け付け、実行を開始する。 */
    public static void main(String[] args) {
      Path root = Path.of(args[0]);
      var ds = new SQLiteDataSource();
      ds.setUrl("jdbc:sqlite:" + root.resolve("state.db"));
      var settings = new CheckpointProperties();
      var repository = new PersistentCheckpointRepository(ds, settings);
      if (args[1].equals("write")) {
        var state =
            PersistentCheckpoint.initial(
                "task", "A", "session", "original-run", root, "complete integration tests");
        var fields = repository.fields(state);
        fields.put(
            "files",
            Map.of(
                root.resolve("artifact.txt").toString(),
                CheckpointReconciler.fingerprint(root.resolve("artifact.txt"))));
        fields.put(
            "operations",
            List.of(
                new PersistentCheckpoint.Operation(
                    "external-call",
                    "deploy",
                    PersistentCheckpoint.OperationStatus.STARTED,
                    "event")));
        repository.save(repository.fields(fields), 0, "before-crash");
        if (!repository.acquire("A", "task", "original-run")) throw new AssertionError();
        // Exit with durable STARTED state and ownership deliberately unreleased.
      } else {
        var factory = new org.springframework.beans.factory.support.StaticListableBeanFactory();
        factory.addBean(
            "router",
            new dev.mikoto2000.rei.core.chat.ConversationInputRouter(
                work -> {},
                (owner, prompt, input) -> {
                  throw new AssertionError("Resume must enqueue, not recursively run");
                }));
        try (var service =
            new PersistentCheckpointService(
                repository,
                new CheckpointReconciler(null),
                settings,
                new dev.mikoto2000.rei.event.InMemoryAgentEventBus(),
                factory.getBeanProvider(dev.mikoto2000.rei.core.chat.ConversationInputRouter.class),
                factory.getBeanProvider(dev.mikoto2000.rei.application.run.RunRegistry.class),
                factory.getBeanProvider(dev.mikoto2000.rei.application.run.RunService.class),
                factory.getBeanProvider(dev.mikoto2000.rei.core.actionplan.ActionPlan.class),
                factory.getBeanProvider(dev.mikoto2000.rei.core.working.WorkingSet.class),
                factory.getBeanProvider(dev.mikoto2000.rei.core.taskstate.TaskState.class),
                factory.getBeanProvider(
                    dev.mikoto2000.rei.core.checkpoint.CheckpointStore.class))) {
          if (repository.leased("A", "task"))
            throw new AssertionError("Dead process retained ownership");
          var check = service.inspect("A", "task");
          if (check.changed().isEmpty() || check.unknownOperations().size() != 1)
            throw new AssertionError("Missing reconciliation");
          var resumed =
              service.resume(
                  "A", "task", dev.mikoto2000.rei.core.chat.AgentRunContext.RequestSource.SHELL);
          var saved = repository.get("A", "task");
          if (saved.runId().equals("original-run")
              || !saved.originalRunId().equals("original-run")
              || !saved.resumedFromRunId().equals("original-run")
              || saved.reconciliation() == null) throw new AssertionError("Missing lineage");
          System.out.println(
              "PASS restart -> change -> reconciliation -> new Run " + resumed.runId());
        }
      }
    }
  }
}
