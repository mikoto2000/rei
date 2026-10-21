package dev.mikoto2000.rei.application.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mikoto2000.rei.application.run.ResourceNotFoundException;
import dev.mikoto2000.rei.application.run.RunRegistry;
import dev.mikoto2000.rei.application.run.RunStatus;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

/** タスク管理サービスの結果と状態の契約を検証するテスト。 */
@Tag("integration")
class TaskManagerServiceTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** 実行一覧を再利用することを確認する。 */
  @Test
  void crossProjectSessionFilteringAndStablePaginationReuseRuns() throws Exception {
    var projects = new ProjectRegistry(root.resolve("projects.json"));
    var a = projects.resolve(Files.createDirectory(root.resolve("a")));
    var b = projects.resolve(Files.createDirectory(root.resolve("b")));
    var runs = new RunRegistry(Clock.systemUTC());
    runs.register(new AgentRunContext("a", "session-a", a.root(), a.id()));
    runs.register(new AgentRunContext("b", "session-b", b.root(), b.id()));
    runs.register(new AgentRunContext("c", "session-a", a.root(), a.id()));
    var tasks = new TaskManagerService(projects, runs, null, null, null, null);
    var first = tasks.list(null, null, 1, null);
    assertThat(first.items()).extracting(TaskView::id).containsExactly("run:a");
    runs.transition("a", RunStatus.RUNNING, null);
    runs.transition("a", RunStatus.COMPLETED, null);
    var next = tasks.list(null, null, 1, first.nextCursor());
    assertThat(next.items()).extracting(TaskView::id).containsExactly("run:b");
    assertThat(tasks.list(a.id(), "session-a", 100, null).items())
        .extracting(TaskView::id)
        .containsExactly("run:a", "run:c");
    assertThatThrownBy(() -> tasks.list(a.id(), null, 1, first.nextCursor()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> tasks.get(b.id(), "session-b", "run:a"))
        .isInstanceOf(ResourceNotFoundException.class);
    assertThatThrownBy(() -> tasks.get(a.id(), "session-b", "run:a"))
        .isInstanceOf(ResourceNotFoundException.class);
    assertThat(tasks.get(a.id(), "session-a", "run:a").status()).isEqualTo("COMPLETED");
  }

  /** 再開について、永続化された付随情報を読み取ることを確認する。 */
  @Test
  void restartReadsDurableMetadataWithoutDispatchAndOmitsUnregisteredRoots() {
    var projects = new ProjectRegistry(root.resolve("projects.json"));
    var project = projects.resolve(root);
    var data = new SQLiteDataSource();
    data.setUrl("jdbc:sqlite:" + root.resolve("runs.db"));
    var runs = new RunRegistry(Clock.systemUTC(), data);
    runs.register(
        new AgentRunContext(
            "read",
            "session",
            root,
            project.id(),
            AgentRunContext.RequestSource.WEB,
            AgentRunContext.Mode.READ_ONLY));
    runs.transition("read", RunStatus.RUNNING, null);
    runs.close();
    var tasks =
        new TaskManagerService(
            projects, new RunRegistry(Clock.systemUTC(), data), null, null, null, null);
    var task = tasks.get(project.id(), "session", "run:read");
    assertThat(task.status()).isEqualTo("UNKNOWN");
    assertThat(task.mode()).isEqualTo("READ_ONLY");
    assertThat(task.resumeSupported()).isFalse();
    assertThat(task.errorSummary()).contains("unknown");
    projects.remove(project.root());
    assertThat(tasks.list(null, null, 50, null).items()).isEmpty();
    assertThatThrownBy(() -> tasks.get(project.id(), "session", "run:read"))
        .isInstanceOf(ResourceNotFoundException.class);
  }

  /** 実行中タスクの操作で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void limitsAndMalformedCursorFailClosed() {
    var tasks =
        new TaskManagerService(
            new ProjectRegistry(root.resolve("projects.json")),
            new RunRegistry(Clock.systemUTC()),
            null,
            null,
            null,
            null);
    for (int limit : new int[] {0, -1, 101})
      assertThatThrownBy(() -> tasks.list(null, null, limit, null))
          .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> tasks.list(null, null, 1, "bad"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> tasks.list("", null, 1, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** 子の実行実行について、親会話を利用することを確認する。 */
  @Test
  void childExecutionUsesParentSessionAndRetainsItsRelationshipAfterRestart() {
    var projects = new ProjectRegistry(root.resolve("projects.json"));
    var project = projects.resolve(root);
    var data = new SQLiteDataSource();
    data.setUrl("jdbc:sqlite:" + root.resolve("children.db"));
    var runs = new RunRegistry(Clock.systemUTC(), data);
    var parent = new AgentRunContext("parent", "session", root, project.id());
    runs.register(parent);
    var child = new AgentRunContext("child", "subagent:child", root, project.id());
    runs.registerChild(child, parent, "reviewer", "durable-child");
    runs.transition("child", RunStatus.RUNNING, null);
    var tasks = new TaskManagerService(projects, runs, null, null, null, null);
    var projected = tasks.get(project.id(), "session", "run:child");
    assertThat(projected.kind()).isEqualTo("SUBAGENT");
    assertThat(projected.parentId()).isEqualTo("run:parent");
    assertThat(projected.results())
        .contains(new TaskView.Reference("SUBAGENT_CHECKPOINT", "durable-child"));
    assertThat(projected.inputSupported()).isFalse();
    assertThat(projected.resumeSupported()).isFalse();
    assertThat(tasks.get(project.id(), "session", "run:parent").childIds())
        .containsExactly("run:child");
    assertThat(tasks.list(project.id(), "session", 100, null).items()).hasSize(2);
    assertThatThrownBy(() -> tasks.get(project.id(), "subagent:child", "run:child"))
        .isInstanceOf(ResourceNotFoundException.class);
    runs.close();
    var restored =
        new TaskManagerService(
            projects, new RunRegistry(Clock.systemUTC(), data), null, null, null, null);
    assertThat(restored.get(project.id(), "session", "run:child").status()).isEqualTo("UNKNOWN");
    assertThat(restored.get(project.id(), "session", "run:child").parentId())
        .isEqualTo("run:parent");
    assertThat(restored.get(project.id(), "session", "run:child").results())
        .contains(new TaskView.Reference("SUBAGENT_CHECKPOINT", "durable-child"));
    assertThat(restored.get(project.id(), "session", "run:parent").childIds())
        .containsExactly("run:child");
  }
}
