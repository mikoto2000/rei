package dev.mikoto2000.rei.application.task;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mikoto2000.rei.application.run.RunRegistry;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.dependency.DependencySpec;
import dev.mikoto2000.rei.core.dependency.PersistentDependencyRepository;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

/** タスク取得元Paginationの結果と状態の契約を検証するテスト。 */
@Tag("integration")
@org.junit.jupiter.api.extension.ExtendWith(
    dev.mikoto2000.rei.testsupport.FixtureTimingExtension.class)
class TaskSourcePaginationTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** 実行中タスクの操作で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void retainedGoalsSchedulesAndCheckpointsAreNotTruncatedToRecentSourceWindows() {
    var fixture = retainedGoalSources();
    var tasks = fixture.tasks();
    var project = fixture.project();
    var ids = new ArrayList<String>();
    String cursor = null;
    do {
      var page = tasks.list(project.id(), null, 100, cursor);
      page.items().forEach(task -> ids.add(task.id()));
      cursor = page.nextCursor();
    } while (cursor != null);
    assertThat(ids).hasSize(1545).doesNotHaveDuplicates().isSorted();
  }

  /** 実行中タスクの操作で、条件が変わっても、引き継ぐべき情報や状態を保持することを確認する。 */
  @Test
  void paginationIncludesRetainedTerminalSourcesBeyondTheirExistingRecentListWindow() {
    var fixture = retainedDependencySources();
    var tasks = fixture.tasks();
    var project = fixture.project();
    var ids = new ArrayList<String>();
    String cursor = null;
    do {
      var page = tasks.list(project.id(), null, 47, cursor);
      assertThat(page.items()).hasSizeLessThanOrEqualTo(47);
      page.items().forEach(task -> ids.add(task.id()));
      cursor = page.nextCursor();
    } while (cursor != null);
    assertThat(ids).hasSize(270).doesNotHaveDuplicates().isSorted();
  }

  /** Creates the same retained source rows independently of pagination assertions. */
  private TaskFixture retainedGoalSources() {
    try (var timing =
        dev.mikoto2000.rei.testsupport.FixtureTimingExtension.phase("task-source-fixture")) {
      var projects = new ProjectRegistry(root.resolve("projects.json"));
      var project = projects.resolve(root);
      var data = new SQLiteDataSource();
      data.setUrl("jdbc:sqlite:" + root.resolve("all.db"));
      var clock = Clock.systemUTC();
      var owner = new AgentRunContext("owner", "session", root, project.id());
      var goals = new dev.mikoto2000.rei.goal.GoalRepository(data, clock);
      var schedules = new dev.mikoto2000.rei.temporal.PersistentAgentScheduler(data, clock);
      for (int i = 0; i < 270; i++) {
        var goal = goals.create(owner, "objective", "output.txt", "b".repeat(64), 1, 1);
        goals.verifiedWithoutRun(project.id(), goal.id());
        try (var binding = dev.mikoto2000.rei.core.chat.AgentRunScope.open(owner)) {
          var schedule = schedules.scheduleAfter(Duration.ofMinutes(5), "action", "session");
          schedules.cancel(project.id(), schedule.id());
        }
      }
      var checkpoints =
          new dev.mikoto2000.rei.checkpoint.PersistentCheckpointRepository(
              data, new dev.mikoto2000.rei.checkpoint.CheckpointProperties());
      for (int i = 0; i < 1005; i++) {
        var checkpoint =
            dev.mikoto2000.rei.checkpoint.PersistentCheckpoint.initial(
                String.format("task-%04d", i),
                project.id(),
                "session",
                String.format("original-%04d", i),
                root,
                "request");
        var fields = checkpoints.fields(checkpoint);
        fields.put("status", "COMPLETED");
        checkpoints.save(checkpoints.fields(fields), 0, "start");
      }
      var tasks =
          new TaskManagerService(
              projects, new RunRegistry(clock), checkpoints, goals, null, schedules);
      return new TaskFixture(project, tasks);
    }
  }

  /** Creates the same retained source rows independently of pagination assertions. */
  private TaskFixture retainedDependencySources() {
    try (var timing =
        dev.mikoto2000.rei.testsupport.FixtureTimingExtension.phase("task-source-fixture")) {
      var projects = new ProjectRegistry(root.resolve("projects.json"));
      var project = projects.resolve(root);
      var data = new SQLiteDataSource();
      data.setUrl("jdbc:sqlite:" + root.resolve("state.db"));
      var clock = Clock.systemUTC();
      var dependencies = new PersistentDependencyRepository(data, clock);
      var owner = new AgentRunContext("run", "session", root, project.id());
      for (int i = 0; i < 270; i++) {
        var created =
            dependencies.create(
                owner,
                new DependencySpec(DependencySpec.Kind.USER_ANSWER, "question", null),
                Duration.ofMinutes(5),
                List.of());
        dependencies.cancel(project.id(), created.id());
      }
      var tasks =
          new TaskManagerService(projects, new RunRegistry(clock), null, null, dependencies, null);
      return new TaskFixture(project, tasks);
    }
  }

  /** A committed source fixture and its unmodified pagination service. */
  private record TaskFixture(
      dev.mikoto2000.rei.core.project.ProjectContext project, TaskManagerService tasks) {}
}
