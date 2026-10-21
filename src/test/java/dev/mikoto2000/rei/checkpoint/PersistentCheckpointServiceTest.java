package dev.mikoto2000.rei.checkpoint;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.AgentRunScope;
import dev.mikoto2000.rei.core.chat.ChatExecutionService;
import dev.mikoto2000.rei.core.chat.ConversationInputRouter;
import dev.mikoto2000.rei.core.chat.RunScopedAdvisor;
import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventType;
import dev.mikoto2000.rei.event.InMemoryAgentEventBus;
import dev.mikoto2000.rei.event.ToolEventCallbackDecorator;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.sqlite.SQLiteDataSource;

/** 保存される再開用の記録サービスの結果と状態の契約を検証するテスト。 */
@Tag("integration")
class PersistentCheckpointServiceTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** 検証に使う保存と検索の窓口。 */
  PersistentCheckpointRepository repository;

  /** 検証に使う通知の連携先。 */
  InMemoryAgentEventBus bus = new InMemoryAgentEventBus();

  /** 検証に使う実行待ち。 */
  List<Runnable> queue = new ArrayList<>();

  /** 検証に使う開始。 */
  List<String> started = new ArrayList<>();

  /** 検証に使うサービス。 */
  PersistentCheckpointService service;

  /** 検証に使う生成元。 */
  org.springframework.beans.factory.support.StaticListableBeanFactory factory;

  /** 検証に使う設定。 */
  CheckpointProperties settings;

  /** 検証に使う所有者。 */
  AgentRunContext owner;

  /** 各検証を独立した状態で始められるよう、依存先と検証データを準備する。 */
  @BeforeEach
  void setup() {
    var source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + root.resolve("state.db"));
    settings = new CheckpointProperties();
    repository = new PersistentCheckpointRepository(source, settings);
    factory = new StaticListableBeanFactory();
    factory.addBean(
        "router",
        new ConversationInputRouter(
            queue::add, (context, prompt, interventions) -> started.add(context.runId())));
    service =
        new PersistentCheckpointService(
            repository,
            new CheckpointReconciler(null),
            settings,
            bus,
            factory.getBeanProvider(ConversationInputRouter.class),
            factory.getBeanProvider(dev.mikoto2000.rei.application.run.RunRegistry.class),
            factory.getBeanProvider(dev.mikoto2000.rei.application.run.RunService.class),
            factory.getBeanProvider(dev.mikoto2000.rei.core.actionplan.ActionPlan.class),
            factory.getBeanProvider(dev.mikoto2000.rei.core.working.WorkingSet.class),
            factory.getBeanProvider(dev.mikoto2000.rei.core.taskstate.TaskState.class),
            factory.getBeanProvider(dev.mikoto2000.rei.core.checkpoint.CheckpointStore.class));
    owner = new AgentRunContext("run", "session", root, "A");
    service.start(owner, "original request");
  }

  /** 作業の中断と再開で、条件が変わっても、引き継ぐべき情報や状態を保持することを確認する。 */
  @Test
  void reviewJobReferencesAndVerifiedCoverageSurviveResume() throws Exception {
    Path data = root.resolve("review-data");
    try (var migration = new dev.mikoto2000.rei.storage.StorageMigrationCoordinator(data)) {
      migration.prepare();
    }
    var ledger =
        new dev.mikoto2000.rei.review.ReviewCoverageLedger(
            new dev.mikoto2000.rei.storage.StorageDatabase(data));
    service.setReviewLedger(ledger);
    var actor = dev.mikoto2000.rei.review.ReviewTools.scope(owner);
    var range =
        new dev.mikoto2000.rei.review.ReviewCoverageLedger.Range(
            "lesson.md",
            "v1",
            new dev.mikoto2000.rei.review.ReviewCoverageLedger.Span(0, 4, 1, 1, 0, 4),
            "hash");
    var job =
        ledger.create(
            actor,
            "correctness",
            "v1",
            List.of(
                new dev.mikoto2000.rei.review.ReviewCoverageLedger.Target(
                    "lesson.md",
                    "v1",
                    1,
                    List.of(
                        new dev.mikoto2000.rei.review.ReviewCoverageLedger.Span(
                            0, 8, 1, 1, 0, 8)))));
    ledger.fetched(job.id(), actor, range);
    ledger.delivered(job.id(), actor, List.of(range));
    ledger.reviewed(job.id(), actor, range, "checked", List.of());
    service.preserveResults(owner, List.of(), null);
    service.finish(owner, "INTERRUPTED");
    assertTrue(
        state().evidence().stream()
            .anyMatch(
                e -> e.origin().equals("REVIEW_JOB_REFERENCE") && e.summary().equals(job.id())));
    var resumed = service.resume("A", state().taskId(), AgentRunContext.RequestSource.SHELL);
    var next = new AgentRunContext(resumed.runId(), "session", root, "A");
    service.start(next, "resume");
    var restored = ledger.get(job.id(), dev.mikoto2000.rei.review.ReviewTools.scope(next));
    assertEquals("REVIEW_PARTIAL", restored.status());
    assertTrue(restored.ranges().getFirst().reviewed());
    assertEquals(range, restored.ranges().getFirst().range());
  }

  /** 進捗について、再開用の記録再開の後も対象の情報が残ることを確認する。 */
  @Test
  void progressSurvivesCheckpointResumeAndStaysIsolated() {
    var ledger = new dev.mikoto2000.rei.core.stagnation.InformationLedger();
    ledger.observeRange("docs/A", "v1", 1, 10001);
    var saved =
        new dev.mikoto2000.rei.core.stagnation.RunExecutionContext.ProgressState(
            ledger.snapshot(),
            new dev.mikoto2000.rei.llm.OutputLimitRunBudget.Snapshot(0, 4, 0, false),
            12,
            2);
    service.preserveResults(owner, List.of(), saved);
    service.finish(owner, "INTERRUPTED");
    var resumed = service.resume("A", state().taskId(), AgentRunContext.RequestSource.SHELL);
    var context = new AgentRunContext(resumed.runId(), "session", root, "A");
    var restored = service.progress(context);
    assertEquals(saved, restored);
    var other = new AgentRunContext("different", "session", root, "A");
    assertNull(service.progress(other));
    var e = new dev.mikoto2000.rei.core.stagnation.InformationLedger();
    e.restore(restored.information());
    assertEquals(
        dev.mikoto2000.rei.core.stagnation.InformationLedger.Status.DUPLICATE,
        e.observeRange("docs/A", "v1", 1, 10001).status());
  }

  /** 進捗を保持することを確認する。 */
  @Test
  void manualHistoryCleanupRetainsProgressAndResumeEvidence() {
    var ledger = new dev.mikoto2000.rei.core.stagnation.InformationLedger();
    ledger.observeRange("docs/A", "v1", 1, 200);
    var progress =
        new dev.mikoto2000.rei.core.stagnation.RunExecutionContext.ProgressState(
            ledger.snapshot(),
            new dev.mikoto2000.rei.llm.OutputLimitRunBudget.Snapshot(0, 4, 0, false),
            12,
            2);
    var event =
        new AgentEventFactory(java.time.Clock.systemUTC())
            .toolStarted("call", "deploy", "deployment")
            .withOwnership(owner);
    bus.publishBoundary(event);
    service.preserveResults(owner, List.of(), progress);
    service.finish(owner, "INTERRUPTED");
    var before = state();
    var preview = service.cleanupHistory(false);
    assertTrue(preview.removedRevisions() > 0);
    assertEquals(before, state());
    service.cleanupHistory(true);
    assertEquals(before, state());
    assertEquals(1, service.historyUsage().revisions());
    assertEquals(
        PersistentCheckpoint.OperationStatus.UNKNOWN, state().operations().getFirst().status());
    var resumed = service.resume("A", before.taskId(), AgentRunContext.RequestSource.SHELL);
    assertEquals(
        progress, service.progress(new AgentRunContext(resumed.runId(), "session", root, "A")));
    assertEquals(before.revision(), resumed.checkpointRevision());
  }

  /** 未指定進捗入力先以前の再開用の記録読み取り可能なおよびについて、形式不明を復元することを確認する。 */
  @Test
  void missingProgressInOldCheckpointIsReadableAndRestoresAsUnknown() {
    var old = repository.fields(state());
    old.remove("progress");
    assertNull(repository.fields(old).progress());
    service.finish(owner, "INTERRUPTED");
    var resumed = service.resume("A", state().taskId(), AgentRunContext.RequestSource.SHELL);
    var e = new dev.mikoto2000.rei.core.stagnation.InformationLedger();
    e.restore(null);
    assertNull(service.progress(new AgentRunContext(resumed.runId(), "session", root, "A")));
    assertEquals(
        dev.mikoto2000.rei.core.stagnation.InformationLedger.Status.UNKNOWN,
        e.observeRange("A", "v1", 1, 2).status());
  }

  /** 作業の中断と再開で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void failedProgressSaveRetainsPreviouslyCommittedProgress() {
    var ledger = new dev.mikoto2000.rei.core.stagnation.InformationLedger();
    ledger.observeRange("A", "v1", 1, 2);
    var saved =
        new dev.mikoto2000.rei.core.stagnation.RunExecutionContext.ProgressState(
            ledger.snapshot(),
            new dev.mikoto2000.rei.llm.OutputLimitRunBudget.Snapshot(0, 4, 0, false),
            0,
            0);
    service.preserveResults(owner, List.of(), saved);
    settings.setMaxSnapshotBytes(1024);
    for (int i = 0; i < 100; i++) ledger.observe(String.format("%064d", i));
    var larger =
        new dev.mikoto2000.rei.core.stagnation.RunExecutionContext.ProgressState(
            ledger.snapshot(), saved.budget(), 0, 0);
    assertThrows(
        CheckpointException.class, () -> service.preserveResults(owner, List.of(), larger));
    assertEquals(saved, repository.get("A", state().taskId()).progress());
  }

  /** 次の検証へ状態を持ち越さないよう、利用した資源と保存先を片付ける。 */
  @AfterEach
  void close() {
    service.close();
  }

  /** 動作モードを保持することを確認する。 */
  @Test
  void restrictedRunPreservesModeAcrossResumeWithoutResettingActiveTask() {
    var plan = new dev.mikoto2000.rei.core.actionplan.ActionPlan();
    var task = new dev.mikoto2000.rei.core.taskstate.TaskState();
    task.start("active implementation");
    factory.addBean("plan", plan);
    factory.addBean("task", task);
    var consultation =
        new AgentRunContext(
            "consult",
            "session",
            root,
            "A",
            AgentRunContext.RequestSource.WEB,
            AgentRunContext.Mode.CONVERSATION);
    service.start(consultation, "question");
    assertEquals("active implementation", task.goal());
    service.finish(consultation, "CANCELLED");
    var checkpoint =
        repository.list("A").stream()
            .filter(s -> s.runId().equals("consult"))
            .findFirst()
            .orElseThrow();
    assertEquals(AgentRunContext.Mode.CONVERSATION, checkpoint.mode());
    var resumed = service.resume("A", checkpoint.taskId(), AgentRunContext.RequestSource.WEB);
    assertEquals(
        AgentRunContext.Mode.CONVERSATION,
        repository.list("A").stream()
            .filter(s -> s.runId().equals(resumed.runId()))
            .findFirst()
            .orElseThrow()
            .mode());
  }

  /** {@code state} の条件を再現するための補助情報を返す。 */
  PersistentCheckpoint state() {
    return repository.list("A").getFirst();
  }

  /** 作業の中断と再開で、保存した情報を、再起動や復元後の処理へ正しく引き継ぐことを確認する。 */
  @Test
  void forcePreviewDoesNotDisableActiveRunPersistence() {
    var before = repository.historyUsage();
    var preview = service.cleanupHistory(false, true);
    assertTrue(preview.force());
    assertEquals(before, repository.historyUsage());
    var event =
        new AgentEventFactory(java.time.Clock.systemUTC())
            .toolStarted("read", "readFile", "read")
            .withOwnership(owner);
    bus.publishBoundary(event);
    assertTrue(repository.historyUsage().revisions() > before.revisions());
  }

  /** 作業の中断と再開で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void forceDeleteKeepsLiveProgressWithoutRecreatingHistoryOrClaimingSaves() {
    var ledger = new dev.mikoto2000.rei.core.stagnation.InformationLedger();
    ledger.observeRange("A", "v1", 1, 2);
    var progress =
        new dev.mikoto2000.rei.core.stagnation.RunExecutionContext.ProgressState(
            ledger.snapshot(),
            new dev.mikoto2000.rei.llm.OutputLimitRunBudget.Snapshot(0, 4, 0, false),
            0,
            0);
    service.preserveResults(owner, List.of(), progress);
    var savedEvents = new ArrayList<AgentEvent>();
    var listener =
        bus.subscribe(
            event -> {
              if (event.type() == AgentEventType.CHECKPOINT_REVISION_SAVED) savedEvents.add(event);
            });
    try {
      service.cleanupHistory(true, true);
      var events = new AgentEventFactory(java.time.Clock.systemUTC());
      bus.publishBoundary(events.toolStarted("read", "readFile", "read").withOwnership(owner));
      var completed =
          events.toolCompleted("read", "readFile", 1, "read result").withOwnership(owner);
      bus.publishBoundary(completed);
      bus.publishBoundary(completed);
      assertEquals(progress, service.progress(owner));
      assertEquals(0, repository.historyUsage().revisions());
      assertTrue(savedEvents.isEmpty());
      assertTrue(
          assertThrows(IllegalStateException.class, () -> service.saveCurrent("A", "session"))
              .getMessage()
              .contains("強制削除"));
      service.finish(owner, "COMPLETED");
      assertTrue(repository.list("A").isEmpty());
      assertTrue(savedEvents.isEmpty());
      var next = new AgentRunContext("new-run", "session", root, "A");
      service.start(next, "new request");
      assertEquals(1, repository.historyUsage().revisions());
      assertEquals("new-run", state().runId());
      service.finish(next, "COMPLETED");
    } finally {
      listener.unsubscribe();
    }
  }

  /** 強制について、保持する不明操作の影響実行可否の判断を削除することを確認する。 */
  @Test
  void forceDeletePreservesUnknownSideEffectGateForAnExecutingResumedRun() {
    var events = new AgentEventFactory(java.time.Clock.systemUTC());
    bus.publishBoundary(events.toolStarted("deploy", "deploy", "deploy").withOwnership(owner));
    service.finish(owner, "INTERRUPTED");
    var resumed = service.resume("A", state().taskId(), AgentRunContext.RequestSource.SHELL);
    var next = new AgentRunContext(resumed.runId(), "session", root, "A");
    service.start(next, "resume");
    service.cleanupHistory(true, true);
    var effectCalls = new java.util.concurrent.atomic.AtomicInteger();
    var callback =
        new org.springframework.ai.tool.ToolCallback() {
          /** 検証用に、作業の中断と再開で参照するツール定義を返す。 */
          public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
            return org.springframework.ai.tool.definition.ToolDefinition.builder()
                .name("deploy")
                .description("deploy")
                .inputSchema("{}")
                .build();
          }

          /** 検証用に、作業の中断と再開の要求を実行し、呼び出し元へ結果を返す。 */
          public String call(String input) {
            effectCalls.incrementAndGet();
            return "deployed";
          }
        };
    try (var scope = AgentRunScope.open(next)) {
      assertThrows(
          IllegalStateException.class,
          () -> new ToolEventCallbackDecorator(callback, events, bus).call("{}"));
      bus.publishBoundary(events.toolStarted("read", "readFile", "read").withOwnership(next));
      bus.publishBoundary(
          events.toolCompleted("read", "readFile", 1, "read result").withOwnership(next));
      assertThrows(
          IllegalStateException.class,
          () -> new ToolEventCallbackDecorator(callback, events, bus).call("{}"));
    }
    assertEquals(0, effectCalls.get());
    assertTrue(service.context(next.runId()).contains("Unknown operations MUST NOT be replayed"));
    assertEquals(0, repository.historyUsage().revisions());
    service.finish(next, "CANCELLED");
    assertEquals(0, repository.historyUsage().revisions());
  }

  /** 失敗強制について、なし無効化活動中実行状態の保存を削除することを確認する。 */
  @Test
  void failedForceDeleteDoesNotDisableActiveRunPersistence() throws Exception {
    var before = repository.historyUsage();
    try (var connection =
            java.sql.DriverManager.getConnection("jdbc:sqlite:" + root.resolve("state.db"));
        var statement = connection.createStatement()) {
      statement.execute(
          "CREATE TRIGGER fail_force BEFORE DELETE ON checkpoint_heads BEGIN SELECT RAISE(ABORT,'injected'); END");
    }
    assertThrows(RuntimeException.class, () -> service.cleanupHistory(true, true));
    assertEquals(before, repository.historyUsage());
    bus.publishBoundary(
        new AgentEventFactory(java.time.Clock.systemUTC())
            .toolStarted("read", "readFile", "read")
            .withOwnership(owner));
    assertTrue(repository.historyUsage().revisions() > before.revisions());
  }

  /** 作業の中断と再開で、競合する要求でも、実行順序と作業の分離を守ることを確認する。 */
  @Test
  void concurrentForceDeleteAndToolBoundaryDoNotRecreateOldCheckpoints() throws Exception {
    var barrier = new java.util.concurrent.CyclicBarrier(2);
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var clear =
          pool.submit(
              () -> {
                barrier.await();
                return service.cleanupHistory(true, true);
              });
      var event =
          pool.submit(
              () -> {
                barrier.await();
                bus.publishBoundary(
                    new AgentEventFactory(java.time.Clock.systemUTC())
                        .toolStarted("read", "readFile", "read")
                        .withOwnership(owner));
                return null;
              });
      clear.get();
      event.get();
    }
    assertEquals(0, repository.historyUsage().revisions());
    service.finish(owner, "COMPLETED");
    assertEquals(0, repository.historyUsage().revisions());
  }

  /** 作業の中断と再開で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void staleTaskProjectionRevisionCannotResumeOrConsumeAnExecutionLease() {
    service.finish(owner, "CANCELLED");
    var saved = state();
    assertThrows(
        dev.mikoto2000.rei.application.state.OperationConflictException.class,
        () ->
            service.resume(
                "A", saved.taskId(), AgentRunContext.RequestSource.WEB, saved.revision() - 1));
    assertTrue(queue.isEmpty());
    assertEquals(saved.revision(), state().revision());
    assertFalse(repository.leased("A", saved.taskId()));
  }

  /** 再開先への切り替えが即時に実行される処理より先になることを確認する。 */
  @Test
  void shellSelectsSessionBeforeImmediateExecutionAndReturnsItsIdentity() {
    service.finish(owner, "CANCELLED");
    var selected = new java.util.concurrent.atomic.AtomicReference<String>("other");
    factory.addBean(
        "router",
        new ConversationInputRouter(
            Runnable::run,
            (context, prompt, interventions) -> {
              assertEquals(context.conversationId(), selected.get());
              started.add(context.runId());
            }));
    var resumed = service.resumeForShell("A", state().taskId(), selected::set);
    assertEquals("session", resumed.sessionId());
    assertEquals(List.of(resumed.runId()), started);
  }

  /** 再開の照合に失敗した場合は表示先を切り替えないことを確認する。 */
  @Test
  void rejectedResumeDoesNotSelectSession() {
    assertThrows(
        IllegalStateException.class,
        () ->
            service.resumeForShell(
                "A",
                state().taskId(),
                context -> {
                  throw new AssertionError("Must not change selection for a busy task");
                }));
    assertTrue(queue.isEmpty());
  }

  /** 作業の中断と再開で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void inspectionNeverExecutesAndResumeCreatesNewRunWithLineageAndLease() {
    service.finish(owner, "CANCELLED");
    String task = state().taskId();
    service.list("A");
    service.inspect("A", task);
    assertTrue(queue.isEmpty());
    var resumed = service.resume("A", task, AgentRunContext.RequestSource.SHELL);
    assertNotEquals(owner.runId(), resumed.runId());
    assertEquals(owner.runId(), resumed.previousRunId());
    assertEquals(owner.conversationId(), resumed.sessionId());
    assertEquals("run", state().resumedFromRunId());
    assertEquals(resumed.checkpointRevision(), state().resumedFromRevision());
    assertThrows(
        IllegalStateException.class,
        () -> service.resume("A", task, AgentRunContext.RequestSource.SHELL));
    queue.removeFirst().run();
    assertEquals(List.of(resumed.runId()), started);
    assertFalse(repository.leased("A", task));
  }

  /** 作業の中断と再開で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void sideEffectWithoutSavedResultRemainsUnknownAndCannotAutomaticallyRepeat() {
    var event =
        new AgentEventFactory(java.time.Clock.systemUTC())
            .toolStarted("call", "deploy", "deployment")
            .withOwnership(owner);
    bus.publishBoundary(event);
    service.finish(owner, "CANCELLED");
    assertEquals(
        PersistentCheckpoint.OperationStatus.UNKNOWN, state().operations().getFirst().status());
    var resumed = service.resume("A", state().taskId(), AgentRunContext.RequestSource.SHELL);
    var context = new AgentRunContext(resumed.runId(), "session", root, "A");
    assertThrows(
        IllegalStateException.class,
        () ->
            bus.publishBoundary(
                new AgentEventFactory(java.time.Clock.systemUTC())
                    .toolStarted("retry", "deploy", "deploy")
                    .withOwnership(context)));
    bus.publishBoundary(
        new AgentEventFactory(java.time.Clock.systemUTC())
            .toolStarted("read", "readFile", "read")
            .withOwnership(context));
    assertTrue(
        service.context(resumed.runId()).contains("Unknown operations MUST NOT be replayed"));
  }

  /** 重複の有無イベント一覧および結果変更前計画更新について、確認済み根拠を保持することを確認する。 */
  @Test
  void duplicateEventsAndResultBeforePlanUpdatePreserveConfirmedEvidence() {
    var factory = new AgentEventFactory(java.time.Clock.systemUTC());
    var started = factory.toolStarted("call", "writeFile", "write").withOwnership(owner);
    bus.publishBoundary(started);
    long revision = state().revision();
    bus.publishBoundary(started);
    assertEquals(revision, state().revision());
    bus.publishBoundary(
        factory.toolCompleted("call", "writeFile", 1, "wrote result").withOwnership(owner));
    service.finish(owner, "FAILED");
    assertEquals(
        PersistentCheckpoint.OperationStatus.SUCCEEDED, state().operations().getFirst().status());
    assertEquals("TOOL_CONFIRMED", state().evidence().getFirst().origin());
    assertEquals("FAILED", state().status());
  }

  /** 作業の中断と再開の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void queuedResumeCancellationReleasesExecutionRightWithoutRunning() {
    service.finish(owner, "CANCELLED");
    var resumed = service.resume("A", state().taskId(), AgentRunContext.RequestSource.WEB);
    var context =
        new AgentRunContext(
            resumed.runId(), "session", root, "A", AgentRunContext.RequestSource.WEB);
    bus.publish(
        new AgentEventFactory(java.time.Clock.systemUTC())
            .runCancelled(resumed.runId(), null)
            .withOwnership(context));
    assertFalse(repository.leased("A", state().taskId()));
    assertEquals("CANCELLED", state().status());
    assertTrue(started.isEmpty());
  }

  /** 最小予算について、不明操作一覧を維持することを確認する。 */
  @Test
  void minimumBudgetKeepsUnknownOperationsAndNextActionBeforeLargeEvidence() {
    settings.setContextCharacters(1024);
    bus.publishBoundary(
        new AgentEventFactory(java.time.Clock.systemUTC())
            .toolStarted("call", "deploy", "deploy")
            .withOwnership(owner));
    service.finish(owner, "CANCELLED");
    var resumed = service.resume("A", state().taskId(), AgentRunContext.RequestSource.SHELL);
    assertTrue(service.context(resumed.runId()).contains("call"));
    assertTrue(service.context(resumed.runId()).contains("nextAction"));
    assertTrue(service.context(resumed.runId()).length() <= 1024);
  }

  /** 実際のモデルツール呼び出し参照先を保持することを確認する。 */
  @Test
  void rawResultsStayOutsideSnapshotAndPreserveActualModelToolCallReference() {
    var raw = new dev.mikoto2000.rei.core.contextbudget.RawToolResultStore(root);
    service.setRawResults(raw);
    String body = "large output ".repeat(100000);
    var message =
        org.springframework.ai.chat.messages.ToolResponseMessage.builder()
            .responses(
                List.of(
                    new org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse(
                        "actual-model-call", "readMultiFile", body)))
            .build();
    service.preserveResults(owner, List.of(message));
    var ref = state().evidence().getFirst();
    assertEquals("RAW_RESULT_REFERENCE", ref.origin());
    assertEquals("actual-model-call", ref.toolCallId());
    assertEquals(body, raw.read("session", ref.summary()).rawResult());
    assertFalse(repository.encode(state()).contains("large output"));
  }

  /** 取得元参照先を保持することを確認する。 */
  @Test
  void
      rawPagesFromRealCallbacksAndLegacyQuotedPagesPreserveSourceReferenceWithoutSavingAnotherBody()
          throws Exception {
    var raw = new dev.mikoto2000.rei.core.contextbudget.RawToolResultStore(root);
    service.setRawResults(raw);
    String body = "委譲レビュー🙂\\\"\r\n".repeat(30);
    String reference = raw.save("session", "run", "delegateTask", "child-call", body);
    var callbacks =
        dev.mikoto2000.rei.core.contextbudget.DeliveryToolRegistration.provider(
                new dev.mikoto2000.rei.core.contextbudget.RawToolResultTools(raw))
            .getToolCallbacks();
    String page;
    try (var scope = AgentRunScope.open(owner)) {
      page =
          callbacks[0].call(
              "{\"rawResultRef\":\"" + reference + "\",\"offset\":0,\"limit\":16000}");
      for (String response :
          List.of(
              page, new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(page))) {
        var message =
            org.springframework.ai.chat.messages.ToolResponseMessage.builder()
                .responses(
                    List.of(
                        new org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse(
                            "page-call", "readRawToolResult", response)))
                .metadata(
                    Map.of(
                        "rei.deliveryPolicies",
                        Map.of(
                            "page-call",
                            dev.mikoto2000.rei.core.contextbudget.ResultDelivery.Kind
                                .RAW_RESULT_PAGE)))
                .build();
        assertDoesNotThrow(() -> service.preserveResults(owner, List.of(message)));
      }
    }
    var evidence =
        state().evidence().stream().filter(e -> e.origin().equals("RAW_RESULT_REFERENCE")).toList();
    assertEquals(1, evidence.size());
    assertEquals(reference, evidence.getFirst().summary());
    assertEquals(1, raw.bodyWriteCount());
    assertEquals(body, raw.read("session", reference).rawResult());
    service.finish(owner, "FAILED");
    var resumed = service.resume("A", state().taskId(), AgentRunContext.RequestSource.SHELL);
    assertTrue(
        state().evidence().stream()
            .anyMatch(
                e -> e.origin().equals("RAW_RESULT_REFERENCE") && e.summary().equals(reference)));
    assertNotNull(resumed.runId());
  }

  /** 作業の中断と再開の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void resumedLlmCancellationSavesProgressReleasesLeaseAndInjectsReferenceSeparately() {
    service.finish(owner, "CANCELLED");
    var prompts = new ArrayList<org.springframework.ai.chat.prompt.Prompt>();
    var model =
        new org.springframework.ai.chat.model.ChatModel() {
          /** 検証用に、作業の中断と再開で参照する選択可能な設定を返す。 */
          public org.springframework.ai.chat.prompt.ChatOptions getOptions() {
            return org.springframework.ai.openai.OpenAiChatOptions.builder().model("test").build();
          }

          /** 検証用に、作業の中断と再開の要求を実行し、呼び出し元へ結果を返す。 */
          public org.springframework.ai.chat.model.ChatResponse call(
              org.springframework.ai.chat.prompt.Prompt prompt) {
            throw new UnsupportedOperationException();
          }

          /** {@code stream} の条件を再現するための補助情報を返す。 */
          public reactor.core.publisher.Flux<org.springframework.ai.chat.model.ChatResponse> stream(
              org.springframework.ai.chat.prompt.Prompt prompt) {
            prompts.add(prompt);
            return reactor.core.publisher.Flux.error(
                new java.util.concurrent.CancellationException());
          }
        };
    var holder = org.mockito.Mockito.mock(dev.mikoto2000.rei.core.service.ModelHolderService.class);
    org.mockito.Mockito.when(holder.get()).thenReturn("test");
    var client =
        org.springframework.ai.chat.client.ChatClient.builder(model)
            .defaultAdvisors(new RunScopedAdvisor(new ResumeContextAdvisor(service)))
            .build();
    var chat =
        new ChatExecutionService(
            client,
            holder,
            new dev.mikoto2000.rei.core.service.CommandCancellationService(),
            Optional.empty(),
            new AgentEventFactory(java.time.Clock.systemUTC()),
            bus);
    org.springframework.test.util.ReflectionTestUtils.setField(chat, "checkpoints", service);
    factory.addBean(
        "router",
        new ConversationInputRouter(
            queue::add, (context, prompt, input) -> chat.execute(context, prompt, input)));
    var resumed = service.resume("A", state().taskId(), AgentRunContext.RequestSource.SHELL);
    queue.removeFirst().run();
    assertEquals("CANCELLED", state().status());
    assertFalse(repository.leased("A", state().taskId()));
    assertEquals(1, prompts.size());
    assertTrue(prompts.getFirst().getSystemMessage().getText().contains("original request"));
    assertFalse(prompts.getFirst().getUserMessage().getText().contains("original request"));
    assertNotEquals("run", resumed.runId());
  }

  /** 作業の中断と再開で、保存した情報を、再起動や復元後の処理へ正しく引き継ぐことを確認する。 */
  @Test
  void crashAfterSideEffectBeforeResultPersistenceCannotRepeat() throws Exception {
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    var callback =
        new org.springframework.ai.tool.ToolCallback() {
          /** 検証用に、作業の中断と再開で参照するツール定義を返す。 */
          public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
            return org.springframework.ai.tool.definition.ToolDefinition.builder()
                .name("deploy")
                .description("deploy")
                .inputSchema("{}")
                .build();
          }

          /** 検証用に、作業の中断と再開の要求を実行し、呼び出し元へ結果を返す。 */
          public String call(String input) {
            calls.incrementAndGet();
            return "published";
          }
        };
    // Reject the third revision: initial, STARTED, then result. The side effect has already
    // happened.
    try (var c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + root.resolve("state.db"));
        var s = c.createStatement()) {
      s.execute(
          "CREATE TRIGGER inject_result_failure BEFORE INSERT ON checkpoint_revisions WHEN NEW.revision=3 BEGIN SELECT RAISE(ABORT,'crash after side effect'); END");
    }
    try (var scope = AgentRunScope.open(owner)) {
      assertThrows(
          RuntimeException.class,
          () ->
              new ToolEventCallbackDecorator(
                      callback, new AgentEventFactory(java.time.Clock.systemUTC()), bus)
                  .call("{}"));
    }
    assertEquals(1, calls.get());
    assertEquals(
        PersistentCheckpoint.OperationStatus.STARTED, state().operations().getFirst().status());
  }

  /** 作業の中断と再開の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void cancellationAtDurablePreToolBoundaryMustNotStartTheSideEffect() {
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    var events = new AgentEventFactory(java.time.Clock.systemUTC());
    var execution =
        new dev.mikoto2000.rei.core.stagnation.RunExecutionContext(
            "run",
            new dev.mikoto2000.rei.llm.OutputLimitRunBudget(1, 5),
            new dev.mikoto2000.rei.core.stagnation.ProgressEvaluator(root),
            events,
            bus);
    execution.setRunContext(owner);
    var cancellation =
        bus.subscribe(
            event -> {
              if (event.type() == AgentEventType.TOOL_STARTED) execution.cancel();
            });
    var callback =
        new org.springframework.ai.tool.ToolCallback() {
          /** 検証用に、作業の中断と再開で参照するツール定義を返す。 */
          public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
            return org.springframework.ai.tool.definition.ToolDefinition.builder()
                .name("deploy")
                .description("deploy")
                .inputSchema("{}")
                .build();
          }

          /** 検証用に、作業の中断と再開の要求を実行し、呼び出し元へ結果を返す。 */
          public String call(String input) {
            calls.incrementAndGet();
            return "published";
          }
        };
    var context =
        new org.springframework.ai.chat.model.ToolContext(
            Map.of(
                dev.mikoto2000.rei.core.stagnation.RunExecutionContext.KEY,
                execution,
                AgentRunContext.class.getName(),
                owner));
    try {
      assertThrows(
          java.util.concurrent.CancellationException.class,
          () -> new ToolEventCallbackDecorator(callback, events, bus).call("{}", context));
      assertEquals(0, calls.get());
    } finally {
      cancellation.unsubscribe();
      service.finish(owner, "CANCELLED");
    }
  }
}
