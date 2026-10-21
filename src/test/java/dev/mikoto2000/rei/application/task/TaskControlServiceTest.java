package dev.mikoto2000.rei.application.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.application.run.ResourceNotFoundException;
import dev.mikoto2000.rei.application.run.RunRegistry;
import dev.mikoto2000.rei.application.run.RunService;
import dev.mikoto2000.rei.application.run.RunStatus;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.ConversationInputRouter;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.InMemoryAgentEventBus;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** タスク制御サービスの結果と状態の契約を検証するテスト。 */
@Tag("integration")
class TaskControlServiceTest {
  /** 中断停止する限定再開用の記録実行およびについて、再開取得元を保持することを確認する。 */
  @Test
  void suspendStopsOnlyTheCheckpointRunAndPreservesTheResumeSource() {
    var projects = new ProjectRegistry(root.resolve("projects.json"));
    var project = projects.resolve(root);
    var source = new org.sqlite.SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + root.resolve("pause.db"));
    var checkpoints =
        new dev.mikoto2000.rei.checkpoint.PersistentCheckpointRepository(
            source, new dev.mikoto2000.rei.checkpoint.CheckpointProperties());
    var saved =
        checkpoints.save(
            dev.mikoto2000.rei.checkpoint.PersistentCheckpoint.initial(
                "checkpoint", project.id(), "session", "active", root, "work"),
            0,
            "start");
    var registry = new RunRegistry(Clock.systemUTC());
    registry.register(new AgentRunContext("active", "session", root, project.id()));
    registry.transition("active", RunStatus.RUNNING, null);
    registry.register(new AgentRunContext("bare", "session", root, project.id()));
    var bus = new InMemoryAgentEventBus();
    var lifecycle =
        new RunService(
            registry,
            bus,
            new AgentEventFactory(Clock.systemUTC()),
            new CommandCancellationService(),
            id -> false);
    var tasks = new TaskManagerService(projects, registry, checkpoints, null, null, null);
    var controls =
        new TaskControlService(tasks, null, lifecycle, registry, null, null, null, null, null);
    assertThat(tasks.get(project.id(), "session", "run:active").suspendSupported()).isTrue();
    assertThat(
            controls
                .suspend(project.id(), "session", "run:active", "active", saved.revision())
                .status())
        .isEqualTo("CANCELLED");
    assertThat(checkpoints.get(project.id(), "checkpoint").revision()).isEqualTo(saved.revision());
    assertThat(tasks.get(project.id(), "session", "run:active").resumeSupported()).isTrue();
    assertThatThrownBy(() -> controls.suspend(project.id(), "session", "run:bare", "bare", 0))
        .isInstanceOf(dev.mikoto2000.rei.application.state.OperationConflictException.class);
    assertThat(registry.get("bare").status()).isEqualTo(RunStatus.QUEUED);
  }

  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** 実行中タスクの操作の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void exactOwnedCancellationUsesExistingLifecycleAndIsIdempotent() {
    var projects = new ProjectRegistry(root.resolve("projects.json"));
    var project = projects.resolve(root);
    var clock = Clock.systemUTC();
    var registry = new RunRegistry(clock);
    var jobs = new ArrayList<Runnable>();
    var router = new ConversationInputRouter(jobs::add, (c, p, q) -> {});
    var bus = new InMemoryAgentEventBus();
    var lifecycle =
        new RunService(
            registry,
            bus,
            new AgentEventFactory(clock),
            new CommandCancellationService(),
            router::cancelQueued);
    for (String id : List.of("one", "two")) {
      var owner = new AgentRunContext(id, "session", root, project.id());
      registry.register(owner);
      router.submit(owner, "work");
    }
    var tasks = new TaskManagerService(projects, registry, null, null, null, null);
    var controls =
        new TaskControlService(tasks, null, lifecycle, registry, router, null, null, null, null);
    var cancelled = controls.cancel(project.id(), "session", "run:one", "one", 0);
    assertThat(cancelled.status()).isEqualTo("CANCELLED");
    assertThat(registry.get("two").status()).isEqualTo(RunStatus.QUEUED);
    assertThat(controls.cancel(project.id(), "session", "run:one", "one", 0).status())
        .isEqualTo("CANCELLED");
    assertThatThrownBy(() -> controls.cancel(project.id(), "foreign", "run:two", "two", 0))
        .isInstanceOf(ResourceNotFoundException.class);
    assertThat(registry.get("two").status()).isEqualTo(RunStatus.QUEUED);
  }

  /** 実行中タスクの操作の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void staleRunTargetAndUnsupportedResumeDoNotDispatchOrCancelAnything() {
    var projects = new ProjectRegistry(root.resolve("projects.json"));
    var project = projects.resolve(root);
    var registry = new RunRegistry(Clock.systemUTC());
    registry.register(new AgentRunContext("current", "session", root, project.id()));
    var lifecycle = mock(RunService.class);
    var checkpoints = mock(dev.mikoto2000.rei.checkpoint.PersistentCheckpointService.class);
    var controls =
        new TaskControlService(
            new TaskManagerService(projects, registry, null, null, null, null),
            null,
            lifecycle,
            registry,
            null,
            checkpoints,
            null,
            null,
            null);
    assertThatThrownBy(() -> controls.cancel(project.id(), "session", "run:current", "previous", 0))
        .isInstanceOf(dev.mikoto2000.rei.application.state.OperationConflictException.class);
    assertThatThrownBy(() -> controls.resume(project.id(), "session", "run:current", "current", 0))
        .isInstanceOf(dev.mikoto2000.rei.application.state.OperationConflictException.class);
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(lifecycle, checkpoints);
  }

  /** 実行中タスクの操作で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void guidanceGoesOnlyToNamedRunNextIterationWithoutCreatingAnotherRun() {
    var projects = new ProjectRegistry(root.resolve("projects.json"));
    var project = projects.resolve(root);
    var registry = new RunRegistry(Clock.systemUTC());
    registry.register(new AgentRunContext("active", "session", root, project.id()));
    registry.transition("active", RunStatus.RUNNING, null);
    var inputs = mock(ConversationInputRouter.class);
    when(inputs.offerIntervention(project.id(), "session", "active", "next input"))
        .thenReturn(true);
    var controls =
        new TaskControlService(
            new TaskManagerService(projects, registry, null, null, null, null),
            null,
            null,
            registry,
            inputs,
            null,
            null,
            null,
            null);
    assertThat(
            controls
                .input(project.id(), "session", "run:active", "active", 0, "next input")
                .runId())
        .isEqualTo("active");
    verify(inputs).offerIntervention(project.id(), "session", "active", "next input");
    assertThat(registry.runIds()).containsExactly("active");
    assertThatThrownBy(
            () -> controls.input(project.id(), "foreign", "run:active", "active", 0, "wrong owner"))
        .isInstanceOf(ResourceNotFoundException.class);
    verifyNoMoreInteractions(inputs);
  }
}
