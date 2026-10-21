package dev.mikoto2000.rei.goal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.checkpoint.CheckpointProperties;
import dev.mikoto2000.rei.checkpoint.CheckpointReconciler;
import dev.mikoto2000.rei.checkpoint.PersistentCheckpoint;
import dev.mikoto2000.rei.checkpoint.PersistentCheckpointRepository;
import dev.mikoto2000.rei.checkpoint.PersistentCheckpointService;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.ChatExecutionResult;
import dev.mikoto2000.rei.core.chat.ChatExecutionService;
import dev.mikoto2000.rei.core.chat.ConversationInputRouter;
import dev.mikoto2000.rei.core.dependency.DependencyHttpProbe;
import dev.mikoto2000.rei.core.dependency.DependencyObservation;
import dev.mikoto2000.rei.core.dependency.DependencyObservationService;
import dev.mikoto2000.rei.core.dependency.DependencySourceProbe;
import dev.mikoto2000.rei.core.dependency.DependencySpec;
import dev.mikoto2000.rei.core.dependency.DependencyState;
import dev.mikoto2000.rei.core.dependency.DependencyWatcherProperties;
import dev.mikoto2000.rei.core.dependency.PersistentDependencyRepository;
import dev.mikoto2000.rei.core.policy.ActionCapability;
import dev.mikoto2000.rei.core.policy.ToolPermissionPolicy;
import dev.mikoto2000.rei.core.policy.ToolPermissionProperties;
import dev.mikoto2000.rei.event.AgentEventType;
import dev.mikoto2000.rei.event.InMemoryAgentEventBus;
import dev.mikoto2000.rei.temporal.AgentScheduleDispatcher;
import dev.mikoto2000.rei.temporal.AgentSchedulerProperties;
import dev.mikoto2000.rei.temporal.PersistentAgentScheduler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 目標待機外部連携の結果と状態の契約を検証するテスト。 */
@Tag("integration")
class GoalWaitIntegrationTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** 検証に使うデータベース接続。 */
  DriverManagerDataSource ds;

  /** 検証に使う目標一覧。 */
  GoalRepository goals;

  /** 検証に使う繰り返し。 */
  GoalLoopService loop;

  /** 検証に使う待機する。 */
  GoalWaitRepository waits;

  /** 検証に使う依存条件一覧。 */
  PersistentDependencyRepository dependencies;

  /** 検証に使う観測内容一覧。 */
  DependencyObservationService observations;

  /** 検証に使うschedules。 */
  PersistentAgentScheduler schedules;

  /** 検証に使う再開用の記録一覧。 */
  PersistentCheckpointRepository checkpoints;

  /** 検証に使う再開用の記録サービス。 */
  PersistentCheckpointService checkpointService;

  /** 検証に使うサービス。 */
  GoalWaitService service;

  /** 検証に使う通知の連携先。 */
  InMemoryAgentEventBus bus;

  /** 検証に使う実行する作業一覧。 */
  ArrayDeque<Runnable> jobs = new ArrayDeque<>();

  /** 検証に使う実行一覧。 */
  AtomicInteger executions = new AtomicInteger();

  /** 検証に使うプロジェクト。 */
  String project = "00000000-0000-0000-0000-000000000001";

  /** 検証に使うファイル一覧。 */
  FileGoalVerifier files = new FileGoalVerifier();

  /** {@code owner} の条件を再現するための補助情報を返す。 */
  AgentRunContext owner() {
    return new AgentRunContext("human", "session", root, project);
  }

  /** 各検証を独立した状態で始められるよう、依存先と検証データを準備する。 */
  @BeforeEach
  void setup() throws Exception {
    ds = new DriverManagerDataSource("jdbc:sqlite:" + root.resolve("state.db"));
    bus = new InMemoryAgentEventBus();
    goals = new GoalRepository(ds, Clock.systemUTC());
    waits = new GoalWaitRepository(ds, Clock.systemUTC());
    dependencies = new PersistentDependencyRepository(ds, Clock.systemUTC());
    schedules = new PersistentAgentScheduler(ds, Clock.systemUTC());
    var probe =
        new DependencySourceProbe(
            files,
            null,
            null,
            (id, u, e) -> new DependencyObservation(id, DependencyState.WAITING, "http_not_ready"),
            Clock.systemUTC());
    observations =
        new DependencyObservationService(
            dependencies,
            probe,
            new DependencyWatcherProperties(false),
            new ToolPermissionPolicy(new ToolPermissionProperties(true, null, null, null)),
            bus);
    checkpoints = new PersistentCheckpointRepository(ds, new CheckpointProperties());
    checkpointService = newCheckpointService();
    loop =
        new GoalLoopService(
            goals,
            files,
            (claim, run, done) ->
                jobs.add(
                    () -> {
                      var current = new AgentRunContext(run, "session", root, project);
                      checkpointService.start(current, "Goal continuation");
                      assertTrue(goals.modelBudget(claim, run).tryReserve());
                      int n = executions.incrementAndGet();
                      try {
                        if (n > 1) Files.writeString(root.resolve("out"), "correct");
                        var eventFactory =
                            new dev.mikoto2000.rei.event.AgentEventFactory(Clock.systemUTC());
                        if (n == 1) {
                          bus.publishBoundary(
                              eventFactory
                                  .toolStarted("effect", "writeFile", "saved partial result")
                                  .withOwnership(current));
                          bus.publishBoundary(
                              eventFactory
                                  .toolCompleted("effect", "writeFile", 1, "saved")
                                  .withOwnership(current));
                        }
                        checkpointService.finish(current, n > 1 ? "COMPLETED" : "FAILED");
                        done.accept(
                            n > 1
                                ? new GoalLoopService.Outcome(
                                    ChatExecutionResult.success("done", false))
                                : new GoalLoopService.Outcome(
                                    ChatExecutionResult.failed("waiting"), "permission_required"));
                      } catch (Exception e) {
                        throw new RuntimeException(e);
                      }
                    }),
            new ToolPermissionProperties(true, null, null, null),
            new GoalEvents(bus, Clock.systemUTC()));
    loop.configureWaitRepository(waits);
    service =
        new GoalWaitService(
            waits,
            goals,
            loop,
            dependencies,
            observations,
            schedules,
            checkpointService,
            checkpoints,
            Clock.systemUTC(),
            new GoalEvents(bus, Clock.systemUTC()));
  }

  /** 指定された条件を再現するための検証対象や入力情報を作成する。 */
  PersistentCheckpointService newCheckpointService() {
    var beans = new StaticListableBeanFactory();
    return new PersistentCheckpointService(
        checkpoints,
        new CheckpointReconciler(null),
        new CheckpointProperties(),
        bus,
        beans.getBeanProvider(ConversationInputRouter.class),
        beans.getBeanProvider(dev.mikoto2000.rei.application.run.RunRegistry.class),
        beans.getBeanProvider(dev.mikoto2000.rei.application.run.RunService.class),
        beans.getBeanProvider(dev.mikoto2000.rei.core.actionplan.ActionPlan.class),
        beans.getBeanProvider(dev.mikoto2000.rei.core.working.WorkingSet.class),
        beans.getBeanProvider(dev.mikoto2000.rei.core.taskstate.TaskState.class),
        beans.getBeanProvider(dev.mikoto2000.rei.core.checkpoint.CheckpointStore.class));
  }

  /** 次の検証へ状態を持ち越さないよう、利用した資源と保存先を片付ける。 */
  @AfterEach
  void close() {
    checkpointService.close();
  }

  /** {@code stopped} の条件を再現するための補助情報を返す。 */
  GoalRepository.Goal stopped() throws Exception {
    String sha =
        HexFormat.of()
            .formatHex(
                java.security.MessageDigest.getInstance("SHA-256")
                    .digest("correct".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    var goal = goals.create(owner(), "complete exact artifact", "out", sha, 4, 8);
    loop.run(project, goal.id());
    jobs.removeFirst().run();
    assertEquals("WAITING_APPROVAL", goals.get(project, goal.id()).status());
    return goals.get(project, goal.id());
  }

  /** 目標の達成管理で、保存した情報を、再起動や復元後の処理へ正しく引き継ぐことを確認する。 */
  @Test
  void restartCarriesConditionsAndConfirmedEffectsAndResumesOnlyOnce() throws Exception {
    var goal = stopped();
    var dependency =
        dependencies.create(
            owner(),
            new DependencySpec(DependencySpec.Kind.FILE_EXISTS, "ready", null),
            Duration.ofHours(1),
            List.of());
    var saved = service.waitFor(project, goal.id(), dependency.id(), "approval_or_job_wait");
    assertEquals(goal.currentRunId(), saved.runId());
    assertEquals("WAITING", saved.state());
    assertFalse(saved.snapshot().progress().verification().satisfied());
    assertEquals(
        PersistentCheckpoint.OperationStatus.SUCCEEDED,
        saved.snapshot().operations().getFirst().status());
    assertThrows(IllegalStateException.class, () -> loop.run(project, goal.id()));
    checkpointService.close();
    checkpointService = newCheckpointService();
    waits = new GoalWaitRepository(ds, Clock.systemUTC());
    service =
        new GoalWaitService(
            waits,
            goals,
            loop,
            dependencies,
            observations,
            schedules,
            checkpointService,
            checkpoints,
            Clock.systemUTC(),
            new GoalEvents(bus, Clock.systemUTC()));
    assertEquals(saved, service.show(project, goal.id()));
    assertThrows(
        IllegalStateException.class, () -> service.resume(project, goal.id(), saved.version()));
    assertEquals(1, executions.get());
    Files.writeString(root.resolve("ready"), "ready");
    var blocked = service.show(project, goal.id());
    service.resume(project, goal.id(), blocked.version());
    assertEquals("RESUMED", service.show(project, goal.id()).state());
    assertThrows(
        IllegalStateException.class,
        () -> service.resume(project, goal.id(), service.show(project, goal.id()).version()));
    jobs.removeFirst().run();
    assertEquals(2, executions.get());
    assertEquals("COMPLETED", goals.get(project, goal.id()).status());
    var lineage = checkpoints.get(project, saved.snapshot().checkpointTask());
    assertEquals(goal.currentRunId(), lineage.resumedFromRunId());
    assertEquals(2, goals.get(project, goal.id()).llmCallsUsed());
  }

  /** 不明影響について、既存の項目確認を必要とすることを確認する。 */
  @Test
  void unknownEffectsRequireExistingItemConfirmationBeforeResume() throws Exception {
    var goal = stopped();
    var old = checkpoints.findByRun(project, goal.currentRunId()).orElseThrow();
    var fields = checkpoints.fields(old);
    fields.put(
        "operations",
        List.of(
            new PersistentCheckpoint.Operation(
                "push",
                "gitPush",
                PersistentCheckpoint.OperationStatus.UNKNOWN,
                "event",
                goal.currentRunId())));
    checkpoints.save(checkpoints.fields(fields), old.revision(), "uncertain");
    var dependency =
        dependencies.create(
            owner(),
            new DependencySpec(DependencySpec.Kind.USER_ANSWER, "continue?", null),
            Duration.ofHours(1),
            List.of());
    var saved = service.waitFor(project, goal.id(), dependency.id(), "approval_wait");
    dependencies.answer(project, dependency.id(), "continue");
    assertThrows(
        IllegalStateException.class, () -> service.resume(project, goal.id(), saved.version()));
    assertTrue(jobs.isEmpty());
    String key = goal.currentRunId() + ":push";
    checkpointService.resolve(
        project,
        old.taskId(),
        key,
        PersistentCheckpoint.OperationStatus.SUCCEEDED,
        key + " confirmed succeeded");
    var current = service.show(project, goal.id());
    service.resume(project, goal.id(), current.version());
    jobs.removeFirst().run();
    assertEquals(2, executions.get());
    assertEquals("COMPLETED", goals.get(project, goal.id()).status());
  }

  /** 目標の達成管理で、再送や重複を、受付と実行の規則に従って扱うことを確認する。 */
  @Test
  void schedulerDispatchUsesTypedBindingAndDuplicateEventsDoNotStartSecondGoal() throws Exception {
    var goal = stopped();
    var dependency =
        dependencies.create(
            owner(),
            new DependencySpec(DependencySpec.Kind.FILE_EXISTS, "ready", null),
            Duration.ofHours(1),
            List.of());
    var wait = service.waitFor(project, goal.id(), dependency.id(), "job_wait");
    Files.writeString(root.resolve("ready"), "ready");
    wait = service.activate(project, goal.id(), wait.version());
    var projects = mock(dev.mikoto2000.rei.core.project.ProjectService.class);
    when(projects.registeredProjects())
        .thenReturn(
            List.of(new dev.mikoto2000.rei.core.project.ProjectContext(project, "fixture", root)));
    var sessions = mock(dev.mikoto2000.rei.application.session.SessionRepository.class);
    when(sessions.findById("session"))
        .thenReturn(
            Optional.of(
                new dev.mikoto2000.rei.application.session.SessionMetadata(
                    "session", project, "fixture", Instant.now(), Instant.now())));
    var chat = mock(ChatExecutionService.class);
    var dispatcher =
        new AgentScheduleDispatcher(
            schedules,
            new AgentSchedulerProperties(true),
            new ToolPermissionProperties(true, null, null, null),
            projects,
            sessions,
            new ConversationInputRouter(jobs::add, (o, p, i) -> {}),
            chat);
    dispatcher.configureGoalWaits(service);
    dispatcher.tick();
    dispatcher.tick();
    assertEquals("RESUMED", service.show(project, goal.id()).state());
    assertEquals("COMPLETED", schedules.get(project, wait.scheduleId()).status());
    assertEquals(1, jobs.size());
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(chat);
    jobs.removeFirst().run();
    dispatcher.tick();
    assertEquals(2, executions.get());
  }

  /** 目標の達成管理の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void terminalDependencyThatRevertsCannotResumeAndCancellationPreventsLaterEvents()
      throws Exception {
    var goal = stopped();
    var dependency =
        dependencies.create(
            owner(),
            new DependencySpec(DependencySpec.Kind.FILE_EXISTS, "ready", null),
            Duration.ofHours(1),
            List.of());
    var wait = service.waitFor(project, goal.id(), dependency.id(), "file_wait");
    Files.writeString(root.resolve("ready"), "ready");
    observations.inspect(project, dependency.id(), false);
    Files.delete(root.resolve("ready"));
    assertThrows(
        IllegalStateException.class, () -> service.resume(project, goal.id(), wait.version()));
    assertTrue(jobs.isEmpty());
    var blocked = service.show(project, goal.id());
    service.cancel(project, goal.id(), blocked.version());
    Files.writeString(root.resolve("ready"), "ready");
    assertThrows(
        IllegalStateException.class,
        () -> service.resume(project, goal.id(), service.show(project, goal.id()).version()));
    assertEquals(1, executions.get());
  }

  /** 目標の達成管理で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({
    "external_process,PROCESS_EXIT",
    "test_job,PROCESS_EXIT",
    "changed_file,FILE_CHANGED",
    "changed_git,GIT_STATE_CHANGED",
    "changed_http,HTTP_STATUS",
    "human_answer,USER_ANSWER"
  })
  void supportedWaitSourcesDoNotResumeUntilFreshConditionMatches(
      String name, DependencySpec.Kind kind) throws Exception {
    var goal = stopped();
    var ready = new java.util.concurrent.atomic.AtomicBoolean();
    var git = mock(dev.mikoto2000.rei.workcontext.WorkContextGit.class);
    when(git.capture(any(), any()))
        .thenAnswer(
            i ->
                new dev.mikoto2000.rei.workcontext.WorkContext.GitState(
                    root.toString(),
                    "main",
                    ready.get() ? "b".repeat(40) : "a".repeat(40),
                    Instant.now()));
    var processes = mock(dev.mikoto2000.rei.core.process.BackgroundProcessManager.class);
    when(processes.statusOwned(anyString(), eq(project), eq("session"), eq(root)))
        .thenAnswer(
            i ->
                new dev.mikoto2000.rei.core.process.BackgroundProcessSnapshot(
                    "job",
                    1,
                    ready.get()
                        ? dev.mikoto2000.rei.core.process.BackgroundProcessStatus.EXITED
                        : dev.mikoto2000.rei.core.process.BackgroundProcessStatus.RUNNING,
                    ready.get() ? 0 : null,
                    Instant.now(),
                    null,
                    0,
                    List.of(),
                    List.of(),
                    true,
                    "fixture"));
    DependencyHttpProbe http =
        (id, u, e) ->
            new DependencyObservation(
                id,
                ready.get() ? DependencyState.COMPLETED : DependencyState.WAITING,
                ready.get() ? "http_matches" : "http_waiting");
    var source = new DependencySourceProbe(files, git, processes, http, Clock.systemUTC());
    var policy =
        new ToolPermissionPolicy(
            new ToolPermissionProperties(
                true, Set.of(ActionCapability.READ, ActionCapability.NETWORK_READ), null, null));
    observations =
        new DependencyObservationService(
            dependencies, source, new DependencyWatcherProperties(false), policy, bus);
    service =
        new GoalWaitService(
            waits,
            goals,
            loop,
            dependencies,
            observations,
            schedules,
            checkpointService,
            checkpoints,
            Clock.systemUTC(),
            new GoalEvents(bus, Clock.systemUTC()));
    DependencySpec spec =
        switch (kind) {
          case PROCESS_EXIT -> new DependencySpec(kind, "job", "0");
          case FILE_CHANGED -> new DependencySpec(kind, "ready", "missing");
          case GIT_STATE_CHANGED -> new DependencySpec(kind, "HEAD", "main\n" + "a".repeat(40));
          case HTTP_STATUS -> new DependencySpec(kind, "https://example.com", "200");
          case USER_ANSWER -> new DependencySpec(kind, "continue?", null);
          default -> throw new IllegalStateException();
        };
    var dependency = dependencies.create(owner(), spec, Duration.ofHours(1), List.of());
    var wait = service.waitFor(project, goal.id(), dependency.id(), name);
    assertThrows(
        IllegalStateException.class, () -> service.resume(project, goal.id(), wait.version()));
    assertTrue(jobs.isEmpty());
    ready.set(true);
    if (kind == DependencySpec.Kind.FILE_CHANGED) Files.writeString(root.resolve("ready"), "ready");
    if (kind == DependencySpec.Kind.USER_ANSWER)
      dependencies.answer(project, dependency.id(), "continue");
    service.resume(project, goal.id(), service.show(project, goal.id()).version());
    jobs.removeFirst().run();
    assertEquals("COMPLETED", goals.get(project, goal.id()).status());
    assertEquals(2, executions.get());
    assertTrue(
        mockingDetails(processes).getInvocations().stream()
            .allMatch(i -> i.getMethod().getName().equals("statusOwned")));
  }

  /** シェル待機について、観測形式のバージョンを利用することを確認する。 */
  @Test
  void shellWaitUsesObservedVersionAndOwningSession() throws Exception {
    var goal = stopped();
    var dependency =
        dependencies.create(
            owner(),
            new DependencySpec(DependencySpec.Kind.FILE_EXISTS, "ready", null),
            Duration.ofHours(1),
            List.of());
    var projects = mock(dev.mikoto2000.rei.core.project.ProjectService.class);
    when(projects.currentContext())
        .thenReturn(new dev.mikoto2000.rei.core.project.ProjectContext(project, "fixture", root));
    when(projects.currentSessionId()).thenReturn("session");
    var command = new GoalCommand(goals, loop, projects);
    command.configureWaits(service);
    var output = new java.io.StringWriter();
    command.setShellOutput(new java.io.PrintWriter(output));
    assertEquals(
        0,
        new picocli.CommandLine(command)
            .execute("wait", goal.id(), "--dependency-id", dependency.id()));
    var wait = service.show(project, goal.id());
    assertTrue(output.toString().contains("WAITING"));
    assertTrue(jobs.isEmpty());
    command = new GoalCommand(goals, loop, projects);
    command.configureWaits(service);
    command.setShellOutput(new java.io.PrintWriter(output));
    assertEquals(2, new picocli.CommandLine(command).execute("wait-resume", goal.id()));
    assertTrue(jobs.isEmpty());
    Files.writeString(root.resolve("ready"), "ready");
    command = new GoalCommand(goals, loop, projects);
    command.configureWaits(service);
    command.setShellOutput(new java.io.PrintWriter(output));
    assertEquals(
        0,
        new picocli.CommandLine(command)
            .execute("wait-resume", goal.id(), "--wait-version", String.valueOf(wait.version())));
    jobs.removeFirst().run();
    assertEquals("COMPLETED", goals.get(project, goal.id()).status());
  }

  /** 目標の達成管理で、保存した情報を、再起動や復元後の処理へ正しく引き継ぐことを確認する。 */
  @Test
  void stoppedButUnreconnectedProcessRequiresInspectionInsteadOfRestart() throws Exception {
    var goal = stopped();
    var saved = checkpoints.findByRun(project, goal.currentRunId()).orElseThrow();
    var fields = checkpoints.fields(saved);
    fields.put(
        "processes",
        List.of(
            new PersistentCheckpoint.ProcessRef(
                "external", 999999, null, "external job", "old-process")));
    checkpoints.save(checkpoints.fields(fields), saved.revision(), "unreconnected-process");
    var dependency =
        dependencies.create(
            owner(),
            new DependencySpec(DependencySpec.Kind.USER_ANSWER, "continue?", null),
            Duration.ofHours(1),
            List.of());
    var wait = service.waitFor(project, goal.id(), dependency.id(), "process_wait");
    dependencies.answer(project, dependency.id(), "continue");
    assertThrows(
        IllegalStateException.class, () -> service.resume(project, goal.id(), wait.version()));
    assertTrue(jobs.isEmpty());
    assertEquals(1, executions.get());
  }

  /** 目標の達成管理で、再送や重複を、受付と実行の規則に従って扱うことを確認する。 */
  @Test
  void duplicateBindingAndStaleWaitVersionCannotAdmitNewRun() throws Exception {
    var goal = stopped();
    var dependency =
        dependencies.create(
            owner(),
            new DependencySpec(DependencySpec.Kind.FILE_EXISTS, "ready", null),
            Duration.ofHours(1),
            List.of());
    var wait = service.waitFor(project, goal.id(), dependency.id(), "file_wait");
    assertThrows(
        IllegalStateException.class,
        () -> service.waitFor(project, goal.id(), dependency.id(), "file_wait"));
    Files.writeString(root.resolve("ready"), "ready");
    assertThrows(
        IllegalStateException.class, () -> service.resume(project, goal.id(), wait.version() + 1));
    assertTrue(jobs.isEmpty());
    assertEquals(1, schedules.list(project).size());
  }

  /** 目標の達成管理で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void dependencyFactsWithNoRunReachExistingNotificationBusAlongsideCheckpointListener()
      throws Exception {
    var goal = stopped();
    var dependency =
        dependencies.create(
            owner(),
            new DependencySpec(DependencySpec.Kind.USER_ANSWER, "continue?", null),
            Duration.ofHours(1),
            List.of());
    service.waitFor(project, goal.id(), dependency.id(), "approval_wait");
    dependencies.answer(project, dependency.id(), "continue");
    var delivered = new ArrayList<dev.mikoto2000.rei.event.AgentEvent>();
    var subscription = bus.subscribe(delivered::add);
    observations.inspect(project, dependency.id(), false);
    observations.flushFacts();
    subscription.unsubscribe();
    assertTrue(dependencies.pendingFacts().isEmpty());
    assertTrue(
        delivered.stream()
            .anyMatch(e -> e.type() == AgentEventType.DEPENDENCY_COMPLETED && e.runId() == null));
  }

  /** 目標の達成管理で、再送や重複を、受付と実行の規則に従って扱うことを確認する。 */
  @Test
  void uncertainAdmissionAfterRestartNeverAutomaticallyReplays() throws Exception {
    var goal = stopped();
    var dependency =
        dependencies.create(
            owner(),
            new DependencySpec(DependencySpec.Kind.USER_ANSWER, "continue?", null),
            Duration.ofHours(1),
            List.of());
    var wait = service.waitFor(project, goal.id(), dependency.id(), "approval_wait");
    dependencies.answer(project, dependency.id(), "continue");
    var uncertain = waits.transition(wait, "RESUMING", "resume_admission_unknown", null);
    var restored = new GoalWaitRepository(ds, Clock.systemUTC());
    assertEquals("RESUMING", restored.get(project, goal.id()).state());
    assertThrows(
        IllegalStateException.class, () -> service.resume(project, goal.id(), uncertain.version()));
    assertTrue(jobs.isEmpty());
    assertEquals(1, executions.get());
    service.cancel(project, goal.id(), uncertain.version());
    assertEquals("CANCELLED", service.show(project, goal.id()).state());
  }

  /** 目標の達成管理の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void cancelledAdmissionCannotClaimGoalOrChargeAnotherAttempt() throws Exception {
    var goal = stopped();
    var dependency =
        dependencies.create(
            owner(),
            new DependencySpec(DependencySpec.Kind.USER_ANSWER, "continue?", null),
            Duration.ofHours(1),
            List.of());
    var wait = service.waitFor(project, goal.id(), dependency.id(), "approval_wait");
    var claimed = waits.transition(wait, "RESUMING", "user_answer_received", null);
    var paused = goals.get(project, goal.id());
    waits.transition(claimed, "CANCELLED", "human_wait_cancelled", null);
    assertThrows(IllegalStateException.class, () -> goals.claim(paused, claimed));
    assertTrue(jobs.isEmpty());
    assertEquals(1, goals.get(project, goal.id()).attempts());
  }

  /** 目標の達成管理で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void checkpointLeaseAndRevisionAreCheckedAgainAtActualGoalAdmission() throws Exception {
    var goal = stopped();
    var saved = checkpoints.findByRun(project, goal.currentRunId()).orElseThrow();
    var newOwner = new AgentRunContext("new", "session", root, project);
    assertThrows(
        IllegalStateException.class,
        () ->
            checkpointService.prepareGoalContinuation(
                newOwner, saved.taskId(), goal.currentRunId(), saved.revision() - 1));
    assertFalse(checkpoints.leased(project, saved.taskId()));
    assertEquals(saved, checkpoints.get(project, saved.taskId()));
    assertTrue(jobs.isEmpty());
  }

  /** 目標の達成管理で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void exhaustedWaitBudgetCannotRestoreCallsOrAttempts() throws Exception {
    var goal = goals.create(owner(), "bounded result", "out", "a".repeat(64), 1, 1);
    loop.run(project, goal.id());
    jobs.removeFirst().run();
    var dependency =
        dependencies.create(
            owner(),
            new DependencySpec(DependencySpec.Kind.USER_ANSWER, "continue?", null),
            Duration.ofHours(1),
            List.of());
    var wait = service.waitFor(project, goal.id(), dependency.id(), "approval_wait");
    dependencies.answer(project, dependency.id(), "continue");
    assertThrows(
        IllegalStateException.class, () -> service.resume(project, goal.id(), wait.version()));
    assertTrue(jobs.isEmpty());
    assertEquals(1, executions.get());
    assertEquals(1, goals.get(project, goal.id()).llmCallsUsed());
    assertEquals(1, goals.get(project, goal.id()).attempts());
    assertEquals("budget_exhausted", service.show(project, goal.id()).lastObservation());
  }

  /** 変更済み利用者完了定義について、明示的な新規関連付けを必要とすることを確認する。 */
  @Test
  void changedHumanCompletionDefinitionRequiresExplicitNewBinding() throws Exception {
    var goal = stopped();
    var dependency =
        dependencies.create(
            owner(),
            new DependencySpec(DependencySpec.Kind.USER_ANSWER, "continue?", null),
            Duration.ofHours(1),
            List.of());
    var wait = service.waitFor(project, goal.id(), dependency.id(), "approval_wait");
    dependencies.answer(project, dependency.id(), "continue");
    goals.defineCompletion(
        owner(),
        goal.id(),
        new GoalCompletionGate.Definition(goal.criteria(), null, List.of(), List.of(), null));
    assertThrows(
        IllegalStateException.class, () -> service.resume(project, goal.id(), wait.version()));
    assertTrue(jobs.isEmpty());
    assertEquals("goal_changed", service.show(project, goal.id()).lastObservation());
  }
}
