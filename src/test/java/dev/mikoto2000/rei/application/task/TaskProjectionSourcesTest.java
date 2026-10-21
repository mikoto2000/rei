package dev.mikoto2000.rei.application.task;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mikoto2000.rei.application.run.RunRegistry;
import dev.mikoto2000.rei.application.run.RunStatus;
import dev.mikoto2000.rei.checkpoint.CheckpointProperties;
import dev.mikoto2000.rei.checkpoint.PersistentCheckpoint;
import dev.mikoto2000.rei.checkpoint.PersistentCheckpointRepository;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.AgentRunScope;
import dev.mikoto2000.rei.core.dependency.DependencySpec;
import dev.mikoto2000.rei.core.dependency.PersistentDependencyRepository;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.goal.GoalRepository;
import dev.mikoto2000.rei.temporal.PersistentAgentScheduler;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

/** タスク表示用の状態取得元一覧の結果と状態の契約を検証するテスト。 */
@Tag("integration")
class TaskProjectionSourcesTest {
  /** 実行中タスクの操作の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void foreignOrUnknownGoalExecutionCannotAdvertiseCancellationOrSuspend() {
    var projects = new ProjectRegistry(root.resolve("projects.json"));
    var project = projects.resolve(root);
    var source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + root.resolve("foreign.db"));
    var goals = new GoalRepository(source, Clock.systemUTC());
    var owner = new AgentRunContext("parent", "session", root, project.id());
    var goal = goals.create(owner, "objective", "output.txt", "a".repeat(64), 2, 2);
    var claim = goals.claim(project.id(), goal.id());
    String id = goals.beginAttempt(claim);
    var missing =
        new TaskManagerService(
            projects, new RunRegistry(Clock.systemUTC()), null, goals, null, null);
    assertThat(missing.get(project.id(), "session", "goal:" + goal.id()).status())
        .isEqualTo("UNKNOWN");
    assertThat(missing.get(project.id(), "session", "goal:" + goal.id()).cancelSupported())
        .isFalse();
    var runs = new RunRegistry(Clock.systemUTC(), source);
    runs.register(new AgentRunContext(id, "session", root, project.id()));
    runs.transition(id, RunStatus.RUNNING, null);
    var observer =
        new TaskManagerService(
            projects, new RunRegistry(Clock.systemUTC(), source), null, goals, null, null);
    var task = observer.get(project.id(), "session", "goal:" + goal.id());
    assertThat(task.status()).isEqualTo("RUNNING");
    assertThat(task.cancelSupported()).isFalse();
    assertThat(task.suspendSupported()).isFalse();
    runs.close();
    assertThat(observer.get(project.id(), "session", "goal:" + goal.id()).status())
        .isEqualTo("UNKNOWN");
  }

  /** 目標待機中および失敗情報再開について、既存の予算上限一覧を維持することを確認する。 */
  @Test
  void goalWaitingAndFailureResumeKeepTheExistingBudgetLimits() {
    var projects = new ProjectRegistry(root.resolve("projects.json"));
    var project = projects.resolve(root);
    var source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + root.resolve("goals.db"));
    var goals = new GoalRepository(source, Clock.systemUTC());
    var runs = new RunRegistry(Clock.systemUTC());
    var tasks = new TaskManagerService(projects, runs, null, goals, null, null);
    var owner = new AgentRunContext("parent", "session", root, project.id());
    for (String status : List.of("WAITING_APPROVAL", "FAILED", "PAUSED")) {
      var goal = goals.create(owner, "objective", "output.txt", "a".repeat(64), 2, 2);
      var claim = goals.claim(project.id(), goal.id());
      goals.beginAttempt(claim);
      goals.stop(claim, status, "reason");
      var task = tasks.get(project.id(), "session", "goal:" + goal.id());
      assertThat(task.status())
          .isEqualTo(
              status.equals("WAITING_APPROVAL")
                  ? "WAITING"
                  : status.equals("PAUSED") ? "SUSPENDED" : status);
      assertThat(task.resumeSupported()).isTrue();
      claim = goals.claim(project.id(), goal.id());
      goals.beginAttempt(claim);
      goals.stop(claim, "FAILED", "limit");
      assertThat(tasks.get(project.id(), "session", "goal:" + goal.id()).resumeSupported())
          .isFalse();
    }
  }

  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** 再開用の記録について、元のタスク識別情報を維持することを確認する。 */
  @Test
  void checkpointKeepsOriginalTaskIdentityAfterResumeAndProjectsProgressWithoutResettingState() {
    var projects = new ProjectRegistry(root.resolve("projects.json"));
    var project = projects.resolve(root);
    var source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + root.resolve("state.db"));
    var checkpoints = new PersistentCheckpointRepository(source, new CheckpointProperties());
    var original =
        PersistentCheckpoint.initial(
            "checkpoint", project.id(), "session", "original", root, "work");
    var fields = checkpoints.fields(original);
    fields.put(
        "plan",
        List.of(Map.of("id", "one", "status", "DONE"), Map.of("id", "two", "status", "TODO")));
    var first = checkpoints.save(checkpoints.fields(fields), 0, "start");
    checkpoints.save(first.resume("resumed"), first.revision(), "resume");
    var runs = new RunRegistry(Clock.systemUTC());
    runs.register(new AgentRunContext("original", "session", root, project.id()));
    runs.transition("original", RunStatus.RUNNING, null);
    runs.transition("original", RunStatus.COMPLETED, null);
    runs.register(new AgentRunContext("resumed", "session", root, project.id()));
    var tasks = new TaskManagerService(projects, runs, checkpoints, null, null, null);
    assertThat(tasks.list(project.id(), null, 100, null).items())
        .extracting(TaskView::id)
        .containsExactly("run:original");
    var task = tasks.get(project.id(), "session", "run:original");
    assertThat(task.runId()).isEqualTo("resumed");
    assertThat(task.checkpointTaskId()).isEqualTo("checkpoint");
    assertThat(task.status()).isEqualTo("QUEUED");
    assertThat(task.progress()).isEqualTo(new TaskView.Progress(1, 2));
    assertThat(checkpoints.get(project.id(), "checkpoint").revision()).isEqualTo(2);
    var restored =
        new TaskManagerService(
            projects,
            new RunRegistry(Clock.systemUTC()),
            new PersistentCheckpointRepository(source, new CheckpointProperties()),
            null,
            null,
            null);
    assertThat(restored.get(project.id(), "session", "run:original").status()).isEqualTo("UNKNOWN");
    assertThat(restored.get(project.id(), "session", "run:original").resumeSupported()).isTrue();
  }

  /** 目標依存条件および実行予定について、永続化された取得元状態一覧を再利用することを確認する。 */
  @Test
  void goalDependencyAndScheduleReuseDurableSourceStatesAndShowTheirParents() {
    var projects = new ProjectRegistry(root.resolve("projects.json"));
    var project = projects.resolve(root);
    var source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + root.resolve("state.db"));
    var clock = Clock.systemUTC();
    var owner = new AgentRunContext("parent", "session", root, project.id());
    var goals = new GoalRepository(source, clock);
    var goal = goals.create(owner, "objective", "output.txt", "a".repeat(64), 3, 4);
    var dependencies = new PersistentDependencyRepository(source, clock);
    var dependency =
        dependencies.create(
            owner,
            new DependencySpec(DependencySpec.Kind.USER_ANSWER, "confirm", null),
            Duration.ofMinutes(5),
            List.of());
    var schedules = new PersistentAgentScheduler(source, clock);
    String schedule;
    try (var binding = AgentRunScope.open(owner)) {
      schedule = schedules.scheduleAfter(Duration.ofMinutes(5), "next iteration", "session").id();
    }
    var runs = new RunRegistry(clock);
    runs.register(owner);
    var tasks = new TaskManagerService(projects, runs, null, goals, dependencies, schedules);
    assertThat(tasks.list(null, null, 100, null).items()).hasSize(4);
    assertThat(tasks.get(project.id(), "session", "dependency:" + dependency.id()).status())
        .isEqualTo("WAITING");
    assertThat(tasks.get(project.id(), "session", "dependency:" + dependency.id()).parentId())
        .isEqualTo("run:parent");
    assertThat(tasks.get(project.id(), "session", "schedule:" + schedule).status())
        .isEqualTo("QUEUED");
    assertThat(tasks.get(project.id(), "session", "schedule:" + schedule).parentId())
        .isEqualTo("run:parent");
    assertThat(tasks.get(project.id(), "session", "goal:" + goal.id()).resumeSupported()).isTrue();
    assertThat(tasks.get(project.id(), "session", "run:parent").dependencyIds())
        .containsExactly(dependency.id());
    assertThat(
            new PersistentDependencyRepository(source, clock)
                .creatorRun(project.id(), dependency.id()))
        .isEqualTo("parent");
    assertThat(new PersistentAgentScheduler(source, clock).creatorRun(project.id(), schedule))
        .isEqualTo("parent");
  }
}
