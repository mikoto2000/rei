package dev.mikoto2000.rei.temporal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.calls;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.AgentRunScope;
import dev.mikoto2000.rei.core.chat.ChatExecutionResult;
import dev.mikoto2000.rei.core.chat.ChatExecutionService;
import dev.mikoto2000.rei.core.chat.ConversationInputRouter;
import dev.mikoto2000.rei.core.chat.RunContinuation;
import dev.mikoto2000.rei.core.policy.ToolPermissionProperties;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** エージェント実行予定実行先の振り分けの結果と状態の契約を検証するテスト。 */
@Tag("integration")
class AgentScheduleDispatcherTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path dir;

  /** 検証に使うschedules。 */
  PersistentAgentScheduler schedules;

  /** 検証に使う識別子。 */
  String id;

  /** 各検証を独立した状態で始められるよう、依存先と検証データを準備する。 */
  @BeforeEach
  void setup() {
    schedules =
        new PersistentAgentScheduler(
            new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("schedule.db")),
            Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
    try (var scope =
        AgentRunScope.open(
            new AgentRunContext("r", "s", dir, "00000000-0000-0000-0000-000000000001"))) {
      id = schedules.scheduleAfter(Duration.ZERO, "inspect build", "s").id();
    }
    schedules.activate("00000000-0000-0000-0000-000000000001", id);
  }

  /** {@code dispatcher} の条件を再現するための補助情報を返す。 */
  AgentScheduleDispatcher dispatcher(
      boolean enabled, boolean permission, AgentScheduleDispatcher.Gateway gateway) {
    return new AgentScheduleDispatcher(
        schedules,
        new AgentSchedulerProperties(enabled),
        new ToolPermissionProperties(permission, null, null, null),
        gateway);
  }

  /** 時刻と期間の解釈で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void neitherDefaultDisabledNorDisabledPolicyCanDispatch() {
    var calls = new AtomicInteger();
    var a = dispatcher(false, true, (entry, done) -> calls.incrementAndGet());
    var b = dispatcher(true, false, (entry, done) -> calls.incrementAndGet());
    a.tick();
    b.tick();
    assertEquals(0, calls.get());
    assertEquals("SCHEDULED", schedules.get("00000000-0000-0000-0000-000000000001", id).status());
  }

  /** 時刻と期間の解釈で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void dispatchIsBoundedAndRecordsActualCompletionWithoutRepeating() {
    var calls = new AtomicInteger();
    var completion = new AtomicReference<Consumer<ChatExecutionResult>>();
    var dispatcher =
        dispatcher(
            true,
            true,
            (entry, done) -> {
              calls.incrementAndGet();
              completion.set(done);
            });
    dispatcher.tick();
    dispatcher.tick();
    assertEquals(1, calls.get());
    assertEquals("RUNNING", schedules.get("00000000-0000-0000-0000-000000000001", id).status());
    completion.get().accept(ChatExecutionResult.success("Done", false));
    dispatcher.tick();
    assertEquals(1, calls.get());
    assertEquals("COMPLETED", schedules.get("00000000-0000-0000-0000-000000000001", id).status());
    assertEquals("Done", schedules.get("00000000-0000-0000-0000-000000000001", id).outcome());
  }

  /** 時刻と期間の解釈の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void dispatchFailureAndCancellationRemainTerminal() {
    var failed =
        dispatcher(
            true,
            true,
            (entry, done) -> {
              throw new IllegalStateException("Project relocated");
            });
    failed.tick();
    failed.tick();
    assertEquals("FAILED", schedules.get("00000000-0000-0000-0000-000000000001", id).status());
    try (var scope =
        AgentRunScope.open(
            new AgentRunContext("r", "s", dir, "00000000-0000-0000-0000-000000000001"))) {
      id = schedules.scheduleAfter(Duration.ZERO, "x", "s").id();
    }
    schedules.activate("00000000-0000-0000-0000-000000000001", id);
    dispatcher(true, true, (entry, done) -> done.accept(ChatExecutionResult.cancelled())).tick();
    assertEquals("CANCELLED", schedules.get("00000000-0000-0000-0000-000000000001", id).status());
  }

  /** 時刻と期間の解釈で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void shellControlsAreProjectScopedAndNeverDispatchDirectly() {
    var projects = org.mockito.Mockito.mock(dev.mikoto2000.rei.core.project.ProjectService.class);
    org.mockito.Mockito.when(projects.currentContext())
        .thenReturn(
            new dev.mikoto2000.rei.core.project.ProjectContext(
                "00000000-0000-0000-0000-000000000001", "test", dir));
    var command = new TimerCommand(schedules, projects);
    var writer = new java.io.StringWriter();
    command.setShellOutput(new java.io.PrintWriter(writer));
    var cli = new picocli.CommandLine(command);
    assertEquals(0, cli.execute("show", id));
    assertTrue(writer.toString().contains("SCHEDULED"));
    assertEquals(0, cli.execute("cancel", id));
    assertEquals("CANCELLED", schedules.get("00000000-0000-0000-0000-000000000001", id).status());
    assertEquals(2, cli.execute("activate", id));
  }

  /** 実際の受付窓口について、取得済み所有者を利用することを確認する。 */
  @Test
  void actualGatewayUsesCapturedOwnerAndExistingProjectFifo() {
    String project = schedules.get("00000000-0000-0000-0000-000000000001", id).projectId();
    var projects = org.mockito.Mockito.mock(dev.mikoto2000.rei.core.project.ProjectService.class);
    org.mockito.Mockito.when(projects.registeredProjects())
        .thenReturn(
            List.of(new dev.mikoto2000.rei.core.project.ProjectContext(project, "test", dir)));
    var sessions =
        org.mockito.Mockito.mock(dev.mikoto2000.rei.application.session.SessionRepository.class);
    org.mockito.Mockito.when(sessions.findById("s"))
        .thenReturn(
            Optional.of(
                new dev.mikoto2000.rei.application.session.SessionMetadata(
                    "s", project, "test", Instant.EPOCH, Instant.EPOCH)));
    var chat = org.mockito.Mockito.mock(ChatExecutionService.class);
    org.mockito.Mockito.when(
            chat.execute(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq("inspect build"),
                org.mockito.ArgumentMatchers.any()))
        .thenReturn(ChatExecutionResult.success("Actual completion", false));
    var jobs = new ArrayDeque<Runnable>();
    var order = new ArrayList<String>();
    var router =
        new ConversationInputRouter(jobs::add, (owner, prompt, queue) -> order.add("existing"));
    router.submit(new AgentRunContext("first", "another-session", dir, project), "existing work");
    var dispatcher =
        new AgentScheduleDispatcher(
            schedules,
            new AgentSchedulerProperties(true),
            new ToolPermissionProperties(true, null, null, null),
            projects,
            sessions,
            router,
            chat);
    dispatcher.tick();
    org.mockito.Mockito.verifyNoInteractions(chat);
    assertEquals(1, jobs.size());
    jobs.remove().run();
    assertEquals(List.of("existing"), order);
    assertEquals(1, jobs.size());
    jobs.remove().run();
    var context = org.mockito.ArgumentCaptor.forClass(AgentRunContext.class);
    org.mockito.Mockito.verify(chat)
        .execute(
            context.capture(),
            org.mockito.ArgumentMatchers.eq("inspect build"),
            org.mockito.ArgumentMatchers.any());
    assertEquals(project, context.getValue().projectId());
    assertEquals("s", context.getValue().conversationId());
    assertEquals(dir.toAbsolutePath(), context.getValue().projectRoot());
    assertEquals("COMPLETED", schedules.get(project, id).status());
  }

  /**
   * 検証条件の再現に使うHarnessの情報。
   *
   * @param projects 検証に使うプロジェクト一覧
   * @param chat 検証に使うチャット
   * @param router 検証に使う入力の振り分け
   * @param jobs 検証に使う実行する作業一覧
   * @param dispatcher 検証に使う実行先の振り分け
   */
  record Harness(
      dev.mikoto2000.rei.core.project.ProjectService projects,
      ChatExecutionService chat,
      ConversationInputRouter router,
      ArrayDeque<Runnable> jobs,
      AgentScheduleDispatcher dispatcher) {}

  /** 時刻と期間の解釈で、条件が変わっても、引き継ぐべき情報や状態を保持することを確認する。 */
  @Test
  void waitingScheduleKeepsItsClaimUntilSavedContinuationCompletes() {
    var h = harness();
    var continuation = mock(RunContinuation.class);
    h.dispatcher().setContinuations(continuation);
    var completed = new AtomicReference<Consumer<ChatExecutionResult>>();
    doAnswer(
            inv -> {
              completed.set(inv.getArgument(2));
              return null;
            })
        .when(continuation)
        .attachParent(any(), isNull(), any());
    when(h.chat().execute(any(), anyString(), any())).thenReturn(ChatExecutionResult.waiting());
    h.dispatcher().tick();
    h.jobs().remove().run();
    h.dispatcher().tick();
    assertEquals("RUNNING", schedules.get("00000000-0000-0000-0000-000000000001", id).status());
    verify(h.chat(), times(1)).execute(any(), anyString(), any());
    verify(continuation, never()).detachParent(any());
    completed.get().accept(ChatExecutionResult.success("integrated reply", false));
    assertEquals("COMPLETED", schedules.get("00000000-0000-0000-0000-000000000001", id).status());
  }

  /** {@code harness} の条件を再現するための補助情報を返す。 */
  Harness harness() {
    String project = "00000000-0000-0000-0000-000000000001";
    var projects = mock(dev.mikoto2000.rei.core.project.ProjectService.class);
    var context = new dev.mikoto2000.rei.core.project.ProjectContext(project, "test", dir);
    when(projects.currentContext()).thenReturn(context);
    when(projects.registeredProjects()).thenReturn(List.of(context));
    var sessions = mock(dev.mikoto2000.rei.application.session.SessionRepository.class);
    when(sessions.findById("s"))
        .thenReturn(
            Optional.of(
                new dev.mikoto2000.rei.application.session.SessionMetadata(
                    "s", project, "test", Instant.EPOCH, Instant.EPOCH)));
    var chat = mock(ChatExecutionService.class);
    var jobs = new ArrayDeque<Runnable>();
    var router = new ConversationInputRouter(jobs::add, (owner, prompt, queue) -> {});
    return new Harness(
        projects,
        chat,
        router,
        jobs,
        new AgentScheduleDispatcher(
            schedules,
            new AgentSchedulerProperties(true),
            new ToolPermissionProperties(true, null, null, null),
            projects,
            sessions,
            router,
            chat));
  }

  /** 登録済み実行予定について、実際の実行受付から終了までの管理を利用することを確認する。 */
  @Test
  void trackedScheduleUsesActualRunLifecycleAndSavedSession() {
    var h = harness();
    String project = "00000000-0000-0000-0000-000000000001";
    var bus = new dev.mikoto2000.rei.event.InMemoryAgentEventBus();
    var registry = new dev.mikoto2000.rei.application.run.RunRegistry(Clock.systemUTC());
    try (var lifecycle =
        new dev.mikoto2000.rei.application.run.RunService(
            registry,
            bus,
            new dev.mikoto2000.rei.event.AgentEventFactory(Clock.systemUTC()),
            new dev.mikoto2000.rei.core.service.CommandCancellationService(),
            h.router()::cancelQueued)) {
      h.dispatcher().configureRunTracking(registry, lifecycle);
      when(h.chat().execute(any(), anyString(), any()))
          .thenAnswer(
              invocation -> {
                var owner = invocation.getArgument(0, AgentRunContext.class);
                assertEquals(
                    dev.mikoto2000.rei.application.run.RunStatus.RUNNING,
                    lifecycle.get(owner.runId()).status());
                return ChatExecutionResult.success("done", false);
              });
      h.dispatcher().tick();
      String run = schedules.get(project, id).runId();
      assertEquals(
          dev.mikoto2000.rei.application.run.RunStatus.QUEUED, lifecycle.get(run).status());
      assertEquals("s", lifecycle.get(run).context().conversationId());
      assertEquals(project, lifecycle.get(run).context().projectId());
      h.jobs().remove().run();
      assertEquals("COMPLETED", schedules.get(project, id).status());
      assertEquals(
          dev.mikoto2000.rei.application.run.RunStatus.COMPLETED, lifecycle.get(run).status());
      verify(h.chat(), times(1)).execute(any(), anyString(), any());
    }
  }

  /** 時刻と期間の解釈の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void normalRunCancellationReleasesQueuedScheduleWithoutExecutingChat() {
    var h = harness();
    String project = "00000000-0000-0000-0000-000000000001";
    var bus = new dev.mikoto2000.rei.event.InMemoryAgentEventBus();
    var registry = new dev.mikoto2000.rei.application.run.RunRegistry(Clock.systemUTC());
    try (var lifecycle =
        new dev.mikoto2000.rei.application.run.RunService(
            registry,
            bus,
            new dev.mikoto2000.rei.event.AgentEventFactory(Clock.systemUTC()),
            new dev.mikoto2000.rei.core.service.CommandCancellationService(),
            h.router()::cancelQueued)) {
      h.dispatcher().configureRunTracking(registry, lifecycle);
      h.router()
          .submitOperation(
              new AgentRunContext("block", "other", dir, project), () -> {}, Runnable::run);
      h.dispatcher().tick();
      String run = schedules.get(project, id).runId();
      assertTrue(lifecycle.cancel(run).accepted());
      assertFalse(lifecycle.cancel(run).accepted());
      assertEquals("CANCELLED", schedules.get(project, id).status());
      h.jobs().remove().run();
      // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
      verifyNoInteractions(h.chat());
      assertTrue(h.jobs().isEmpty());
      String next;
      try (var scope = AgentRunScope.open(new AgentRunContext("source", "s", dir, project))) {
        next = schedules.scheduleAfter(Duration.ZERO, "next", "s").id();
      }
      schedules.activate(project, next);
      h.dispatcher().tick();
      assertEquals("RUNNING", schedules.get(project, next).status());
    }
  }

  /** 時刻と期間の解釈の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void cancellationBeforeWorkerStartsSkipsChatAndFinishesSavedSchedule() {
    var h = harness();
    String project = "00000000-0000-0000-0000-000000000001";
    var bus = new dev.mikoto2000.rei.event.InMemoryAgentEventBus();
    var registry = new dev.mikoto2000.rei.application.run.RunRegistry(Clock.systemUTC());
    try (var lifecycle =
        new dev.mikoto2000.rei.application.run.RunService(
            registry,
            bus,
            new dev.mikoto2000.rei.event.AgentEventFactory(Clock.systemUTC()),
            new dev.mikoto2000.rei.core.service.CommandCancellationService(),
            h.router()::cancelQueued)) {
      h.dispatcher().configureRunTracking(registry, lifecycle);
      h.dispatcher().tick();
      String run = schedules.get(project, id).runId();
      assertTrue(lifecycle.cancel(run).accepted());
      h.jobs().remove().run();
      assertEquals("CANCELLED", schedules.get(project, id).status());
      // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
      verifyNoInteractions(h.chat());
      assertEquals(
          dev.mikoto2000.rei.application.run.RunStatus.CANCELLED, lifecycle.get(run).status());
    }
  }

  /** 時刻と期間の解釈で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void failedTrackedChatHasOneTerminalEventAndNoSuccessfulSchedule() {
    var h = harness();
    String project = "00000000-0000-0000-0000-000000000001";
    var bus = new dev.mikoto2000.rei.event.InMemoryAgentEventBus();
    var events = new ArrayList<dev.mikoto2000.rei.event.AgentEvent>();
    bus.subscribe(events::add);
    var registry = new dev.mikoto2000.rei.application.run.RunRegistry(Clock.systemUTC());
    try (var lifecycle =
        new dev.mikoto2000.rei.application.run.RunService(
            registry,
            bus,
            new dev.mikoto2000.rei.event.AgentEventFactory(Clock.systemUTC()),
            new dev.mikoto2000.rei.core.service.CommandCancellationService(),
            h.router()::cancelQueued)) {
      h.dispatcher().configureRunTracking(registry, lifecycle);
      when(h.chat().execute(any(), anyString(), any()))
          .thenReturn(ChatExecutionResult.failed("failed"));
      h.dispatcher().tick();
      String run = schedules.get(project, id).runId();
      h.jobs().remove().run();
      assertEquals("FAILED", schedules.get(project, id).status());
      assertEquals(
          dev.mikoto2000.rei.application.run.RunStatus.FAILED, lifecycle.get(run).status());
      assertEquals(
          1,
          events.stream()
              .filter(
                  event ->
                      run.equals(event.runId())
                          && event.type()
                              == dev.mikoto2000.rei.event.AgentEventType.AGENT_RUN_FAILED)
              .count());
      h.dispatcher().tick();
      verify(h.chat(), times(1)).execute(any(), anyString(), any());
    }
  }

  /** 時刻と期間の解釈で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void currentQueuedAndExecutingSchedulesCannotBeReconciled() {
    var h = harness();
    String project = "00000000-0000-0000-0000-000000000001";
    h.dispatcher().tick();
    String run = schedules.get(project, id).runId();
    assertThrows(IllegalStateException.class, () -> h.dispatcher().reconcile(project, id, run));
    when(h.chat().execute(any(), anyString(), any()))
        .thenAnswer(
            invocation -> {
              assertThrows(
                  IllegalStateException.class, () -> h.dispatcher().reconcile(project, id, run));
              return ChatExecutionResult.success("done", false);
            });
    h.jobs().remove().run();
    assertEquals("COMPLETED", schedules.get(project, id).status());
  }

  /** 実行先の振り分けを解放することを確認する。 */
  @Test
  void shellReconciliationAfterQueueRemovalReleasesDispatcherWithoutReplayingOldJob() {
    var h = harness();
    String project = "00000000-0000-0000-0000-000000000001";
    h.dispatcher().tick();
    String run = schedules.get(project, id).runId();
    assertTrue(h.router().cancelQueued(run));
    var command = new TimerCommand(schedules, h.projects(), h.dispatcher());
    command.setShellOutput(new java.io.PrintWriter(new java.io.StringWriter()));
    assertEquals(2, new picocli.CommandLine(command).execute("reconcile", id, "--run-id", run));
    command = new TimerCommand(schedules, h.projects(), h.dispatcher());
    command.setShellOutput(new java.io.PrintWriter(new java.io.StringWriter()));
    assertEquals(
        0,
        new picocli.CommandLine(command)
            .execute("reconcile", id, "--run-id", run, "--acknowledge-uncertain-side-effects"));
    assertEquals("FAILED", schedules.get(project, id).status());
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(h.chat());
    String next;
    try (var scope = AgentRunScope.open(new AgentRunContext("source", "s", dir, project))) {
      next = schedules.scheduleAfter(Duration.ZERO, "next", "s").id();
    }
    schedules.activate(project, next);
    h.dispatcher().tick();
    h.jobs().remove().run();
    verifyNoInteractions(h.chat());
    when(h.chat().execute(any(), eq("next"), any()))
        .thenReturn(ChatExecutionResult.success("new", false));
    h.jobs().remove().run();
    assertEquals("COMPLETED", schedules.get(project, next).status());
  }

  /** 時刻と期間の解釈で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void staleCompletionCannotReleaseNewDispatcherClaim() {
    String project = "00000000-0000-0000-0000-000000000001";
    var callbacks = new ArrayList<Consumer<ChatExecutionResult>>();
    var dispatcher =
        new AgentScheduleDispatcher(
            schedules,
            new AgentSchedulerProperties(true),
            new ToolPermissionProperties(true, null, null, null),
            (entry, done) -> callbacks.add(done),
            entry -> false);
    dispatcher.tick();
    String run = schedules.get(project, id).runId();
    dispatcher.reconcile(project, id, run);
    String next, third;
    try (var scope = AgentRunScope.open(new AgentRunContext("source", "s", dir, project))) {
      next = schedules.scheduleAfter(Duration.ZERO, "next", "s").id();
    }
    try (var scope = AgentRunScope.open(new AgentRunContext("source", "other", dir, project))) {
      third = schedules.scheduleAfter(Duration.ZERO, "third", "other").id();
    }
    schedules.activate(project, next);
    dispatcher.tick();
    schedules.activate(project, third);
    callbacks.getFirst().accept(ChatExecutionResult.success("late", false));
    dispatcher.tick();
    assertEquals(2, callbacks.size());
    assertEquals("FAILED", schedules.get(project, id).status());
    callbacks.get(1).accept(ChatExecutionResult.success("new", false));
    dispatcher.tick();
    assertEquals(3, callbacks.size());
  }
}
