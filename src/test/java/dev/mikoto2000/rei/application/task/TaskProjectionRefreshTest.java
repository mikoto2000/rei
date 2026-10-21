package dev.mikoto2000.rei.application.task;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mikoto2000.rei.application.run.RunRegistry;
import dev.mikoto2000.rei.application.run.RunStatus;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import java.nio.file.Path;
import java.time.Clock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

/** タスク表示用の状態再確認の結果と状態の契約を検証するテスト。 */
@Tag("integration")
class TaskProjectionRefreshTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** 実行中タスクの操作で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void liveObserverDetectsOwnerCrashEvenWhenSavedSnapshotHasNotChanged() throws Exception {
    var projects = new ProjectRegistry(root.resolve("projects.json"));
    var project = projects.resolve(root);
    var process =
        new ProcessBuilder(
                dev.mikoto2000.rei.testsupport.JavaFixtureCommand.command(
                    root, OwnerProbe.class, root.toString(), project.id()))
            .redirectErrorStream(true)
            .redirectOutput(root.resolve("owner.log").toFile())
            .start();
    try {
      long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
      while (!java.nio.file.Files.exists(root.resolve("ready")) && System.nanoTime() < deadline)
        Thread.sleep(10);
      assertThat(java.nio.file.Files.exists(root.resolve("ready"))).isTrue();
      var source = new SQLiteDataSource();
      source.setUrl("jdbc:sqlite:" + root.resolve("owner.db"));
      var observer = new RunRegistry(Clock.systemUTC(), source);
      assertThat(observer.get("child").status()).isEqualTo(RunStatus.RUNNING);
      process.destroyForcibly();
      assertThat(process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      assertThat(observer.get("child").status()).isEqualTo(RunStatus.UNKNOWN);
    } finally {
      if (process.isAlive()) process.destroyForcibly();
    }
  }

  /** 検証条件を再現するための所有者利用可否の確認。 */
  public static class OwnerProbe {
    /** 検証用に、実行中タスクの操作の起動要求を受け付け、実行を開始する。 */
    public static void main(String[] args) throws Exception {
      Path root = Path.of(args[0]);
      var source = new SQLiteDataSource();
      source.setUrl("jdbc:sqlite:" + root.resolve("owner.db"));
      var owner = new RunRegistry(Clock.systemUTC(), source);
      owner.register(new AgentRunContext("child", "session", root, args[1]));
      owner.transition("child", RunStatus.RUNNING, null);
      java.nio.file.Files.writeString(root.resolve("ready"), "ready");
      Thread.sleep(60000);
    }
  }

  /** 実行中タスクの操作で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void observerReadsOwnerUpdatesAndRollbackInsteadOfDisplayingStaleRunningTasks() {
    var projects = new ProjectRegistry(root.resolve("projects.json"));
    var project = projects.resolve(root);
    var source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + root.resolve("runs.db"));
    var clock = Clock.systemUTC();
    var owner = new RunRegistry(clock, source);
    owner.register(new AgentRunContext("active", "session", root, project.id()));
    owner.transition("active", RunStatus.RUNNING, null);
    owner.register(new AgentRunContext("rejected", "session", root, project.id()));
    var observer = new RunRegistry(clock, source);
    var tasks = new TaskManagerService(projects, observer, null, null, null, null);
    assertThat(tasks.get(project.id(), "session", "run:active").status()).isEqualTo("RUNNING");
    assertThat(tasks.get(project.id(), "session", "run:active").cancelSupported()).isFalse();
    assertThat(tasks.get(project.id(), "session", "run:active").inputSupported()).isFalse();
    owner.transition("active", RunStatus.COMPLETED, null);
    owner.forget("rejected");
    assertThat(tasks.get(project.id(), "session", "run:active").status()).isEqualTo("COMPLETED");
    assertThat(tasks.list(project.id(), null, 100, null).items())
        .extracting(TaskView::id)
        .containsExactly("run:active");
    projects.relocate(project.id(), root.getParent());
    assertThat(tasks.list(project.id(), null, 100, null).items()).isEmpty();
  }
}
