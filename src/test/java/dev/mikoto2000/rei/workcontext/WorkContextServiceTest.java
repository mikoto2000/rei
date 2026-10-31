package dev.mikoto2000.rei.workcontext;

import static dev.mikoto2000.rei.workcontext.WorkContext.Evidence;
import static dev.mikoto2000.rei.workcontext.WorkContext.Kind;
import static dev.mikoto2000.rei.workcontext.WorkContext.Origin;
import static dev.mikoto2000.rei.workcontext.WorkContext.Status;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.anyList;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.application.session.SessionMetadata;
import dev.mikoto2000.rei.application.session.SessionRepository;
import dev.mikoto2000.rei.conversation.ConversationTurnStore;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.project.ProjectContext;
import dev.mikoto2000.rei.core.project.ProjectService;
import dev.mikoto2000.rei.event.ProjectAgentEventStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

/** 作業文脈サービスの結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class WorkContextServiceTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path temp;

  /** 検証に使うリポジトリ。 */
  WorkContextRepository repo;

  /** 検証に使う会話一覧。 */
  SessionRepository sessions;

  /** 検証に使うプロジェクト一覧。 */
  ProjectService projects;

  /** 検証に使うやり取り一覧。 */
  ConversationTurnStore turns;

  /** 検証に使う情報の抽出。 */
  WorkContextExtractor extractor;

  /** 検証に使うサービス。 */
  WorkContextService service;

  /** 検証に使うa、b。 */
  ProjectContext a, b;

  /** 検証に使うこの機能の動作設定。 */
  WorkContextProperties properties = new WorkContextProperties(false, true, 1200, 12000, 2, 20);

  /** 各検証を独立した状態で始められるよう、依存先と検証データを準備する。 */
  @BeforeEach
  void setup() throws Exception {
    a =
        new ProjectContext(
            UUID.randomUUID().toString(), "same", Files.createDirectories(temp.resolve("a")));
    b =
        new ProjectContext(
            UUID.randomUUID().toString(), "same", Files.createDirectories(temp.resolve("b")));
    var ds = new SQLiteDataSource();
    ds.setUrl("jdbc:sqlite:" + temp.resolve("work.db"));
    repo = new WorkContextRepository(ds);
    sessions = mock(SessionRepository.class);
    projects = mock(ProjectService.class);
    extractor = mock(WorkContextExtractor.class);
    when(projects.registeredProjects()).thenReturn(List.of(a, b));
    when(projects.currentContext()).thenReturn(b);
    when(sessions.findById("session-a"))
        .thenReturn(
            Optional.of(
                new SessionMetadata("session-a", a.id(), "title", Instant.now(), Instant.now())));
    turns = ConversationTurnStore.inMemory();
    var context = new AgentRunContext("run-a", "session-a", a.root(), a.id());
    turns.start(context, "implement");
    turns.finish(context, ConversationTurnStore.Status.COMPLETED, "implemented; tests not run");
    when(extractor.extract(anyList(), anyList(), anyString()))
        .thenAnswer(
            call -> {
              List<Evidence> e = call.getArgument(0);
              return List.of(
                  new WorkContextCandidate(
                      "ADD",
                      null,
                      Kind.PENDING,
                      "test Native",
                      "not run",
                      Status.OPEN,
                      List.of(e.getFirst().id())));
            });
    service =
        new WorkContextService(
            repo,
            sessions,
            projects,
            turns,
            new ProjectAgentEventStore(temp),
            extractor,
            new WorkContextGit(),
            properties,
            Clock.systemUTC());
  }

  @Test
  void blockedExtractionDoesNotBlockUserEditAndCannotOverwriteItsRevision() throws Exception {
    var old = service.update("session-a", "run-a").orElseThrow();
    var run = new AgentRunContext("later", "session-a", a.root(), a.id());
    turns.start(run, "new work");
    turns.finish(run, ConversationTurnStore.Status.COMPLETED, "finished later");
    var entered = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    doAnswer(
            call -> {
              entered.countDown();
              if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                throw new IllegalStateException("timeout");
              return List.of();
            })
        .when(extractor)
        .extract(anyList(), anyList(), anyString());
    try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var update = executor.submit(() -> service.update("session-a", "later"));
      assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
      try {
        var now = Instant.now();
        var evidence =
            new Evidence(
                "user-edit",
                Origin.USER,
                "session-a",
                "later",
                "later",
                null,
                null,
                null,
                now,
                now,
                "explicit correction");
        var edited =
            executor
                .submit(
                    () ->
                        service.edit(
                            a.id(),
                            old.revision(),
                            old.items().getFirst().id(),
                            "STATUS",
                            null,
                            "user verified",
                            Status.COMPLETED,
                            evidence))
                .get(2, java.util.concurrent.TimeUnit.SECONDS);
        release.countDown();
        var error =
            assertThrows(
                java.util.concurrent.ExecutionException.class,
                () -> update.get(5, java.util.concurrent.TimeUnit.SECONDS));
        assertTrue(error.getCause() instanceof ConcurrentModificationException);
        assertEquals(edited, service.current(a.id()).orElseThrow());
        assertFalse(edited.processedRuns().contains("later"));
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  void explicitRunBatchProcessesOnlyItsRequestedEvidenceOnce() {
    var later = new AgentRunContext("run-batch", "session-a", a.root(), a.id());
    turns.start(later, "batch request");
    turns.finish(later, ConversationTurnStore.Status.COMPLETED, "batch result");
    var unrelated = new AgentRunContext("unrelated", "session-a", a.root(), a.id());
    turns.start(unrelated, "unrelated request");
    turns.finish(unrelated, ConversationTurnStore.Status.COMPLETED, "unrelated result");
    var result =
        service.updateRuns("session-a", java.util.Set.of("run-a", "run-batch")).orElseThrow();
    assertTrue(result.processedRuns().containsAll(List.of("run-a", "run-batch")));
    assertFalse(result.processedRuns().contains("unrelated"));
    service.updateRuns("session-a", java.util.Set.of("run-a", "run-batch"));
    verify(extractor, times(1)).extract(anyList(), anyList(), anyString());
  }

  /** 作業文脈の引き継ぎで、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void sessionOwnershipWinsOverChangedUiProjectAndRepeatedRunsSkipLlm() {
    service.update("session-a", null);
    assertTrue(repo.current(b.id()).isEmpty());
    assertEquals(1, repo.current(a.id()).orElseThrow().items().size());
    service.update("session-a", null);
    verify(extractor, times(1)).extract(anyList(), anyList(), anyString());
  }

  /** 直近状態の記録を維持することを確認する。 */
  @Test
  void extractionFailureAndUnknownSourcesKeepLastSnapshot() {
    service.update("session-a", null);
    var old = repo.current(a.id()).orElseThrow();
    var context = new AgentRunContext("run-2", "session-a", a.root(), a.id());
    turns.start(context, "more");
    turns.finish(context, ConversationTurnStore.Status.FAILED);
    doThrow(new IllegalArgumentException("invalid JSON"))
        .when(extractor)
        .extract(anyList(), anyList(), anyString());
    assertThrows(IllegalArgumentException.class, () -> service.update("session-a", null));
    assertEquals(old, repo.current(a.id()).orElseThrow());
    doReturn(
            List.of(
                new WorkContextCandidate(
                    "ADD", null, Kind.PENDING, "invented", "", Status.OPEN, List.of("fake"))))
        .when(extractor)
        .extract(anyList(), anyList(), anyString());
    assertThrows(IllegalArgumentException.class, () -> service.update("session-a", null));
    assertEquals(old, repo.current(a.id()).orElseThrow());
  }

  /** 作業文脈の引き継ぎで、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void missingSessionAndMissingProjectDoNotCreateDirectories() {
    when(sessions.findById("missing")).thenReturn(Optional.empty());
    assertThrows(IllegalArgumentException.class, () -> service.update("missing", null));
    assertThrows(IllegalArgumentException.class, () -> service.update(null, null));
    when(projects.registeredProjects()).thenReturn(List.of(b));
    assertThrows(IllegalArgumentException.class, () -> service.update("session-a", null));
  }

  /** 作業文脈の引き継ぎで、競合する要求でも、実行順序と作業の分離を守ることを確認する。 */
  @Test
  void concurrentSessionsMergeWithoutLostItemsAndExplicitEditsRetainHistory() throws Exception {
    when(sessions.findById("session-2"))
        .thenReturn(
            Optional.of(
                new SessionMetadata("session-2", a.id(), "second", Instant.now(), Instant.now())));
    var run = new AgentRunContext("run-2", "session-2", a.root(), a.id());
    turns.start(run, "second task");
    turns.finish(run, ConversationTurnStore.Status.COMPLETED);
    doAnswer(
            call -> {
              List<Evidence> e = call.getArgument(0);
              return List.of(
                  new WorkContextCandidate(
                      "ADD",
                      null,
                      Kind.PENDING,
                      e.getFirst().text(),
                      "",
                      Status.OPEN,
                      List.of(e.getFirst().id())));
            })
        .when(extractor)
        .extract(anyList(), anyList(), anyString());
    try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var one = workers.submit(() -> service.update("session-a", null));
      var two = workers.submit(() -> service.update("session-2", null));
      one.get(5, java.util.concurrent.TimeUnit.SECONDS);
      two.get(5, java.util.concurrent.TimeUnit.SECONDS);
    }
    var old = repo.current(a.id()).orElseThrow();
    assertEquals(2, old.items().size());
    assertEquals(2, old.processedRuns().size());
    var id = old.items().getFirst().id();
    var now = Instant.now();
    var evidence =
        new Evidence(
            "explicit",
            Origin.USER,
            "session-a",
            "run-a",
            "run-a",
            null,
            null,
            null,
            now,
            now,
            "complete it");
    var complete =
        service.edit(
            a.id(),
            old.revision(),
            id,
            "STATUS",
            null,
            "verified by user",
            Status.COMPLETED,
            evidence);
    var reopen =
        service.edit(
            a.id(), complete.revision(), id, "STATUS", null, "more work", Status.OPEN, evidence);
    var withdrawn =
        service.edit(
            a.id(),
            reopen.revision(),
            id,
            "STATUS",
            null,
            "cancel task",
            Status.WITHDRAWN,
            evidence);
    assertEquals(Status.WITHDRAWN, withdrawn.items().getFirst().status());
    assertEquals(Status.OPEN, service.revision(a.id(), old.revision()).items().getFirst().status());
    assertThrows(
        ConcurrentModificationException.class,
        () ->
            service.edit(
                a.id(), old.revision(), id, "CORRECT", "stale", "", Status.OPEN, evidence));
  }

  /** 作業文脈の引き継ぎの中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void cancellationAndWorkTimeMetadataAreRetainedWithoutProjectDirectoryCreation() {
    var run = new AgentRunContext("run-meta", "session-a", a.root(), a.id());
    turns.start(run, "metadata");
    service.captureRunStart(run);
    var captured = turns.read("session-a").getLast().metadata().get("workContext.gitCapturedAt");
    turns.finish(run, ConversationTurnStore.Status.CANCELLED);
    assertEquals(
        captured, turns.read("session-a").getLast().metadata().get("workContext.gitCapturedAt"));
    Thread.currentThread().interrupt();
    try {
      assertThrows(
          java.util.concurrent.CancellationException.class,
          () -> service.update("session-a", null));
    } finally {
      Thread.interrupted();
    }
    assertTrue(repo.current(a.id()).isEmpty());
    service.update("session-a", null);
    assertEquals(Instant.parse(captured), repo.current(a.id()).orElseThrow().git().capturedAt());
  }

  /** 明示的なについて、利用可否含める対象活動中会話を保存することを確認する。 */
  @Test
  void explicitSaveCanIncludeActiveSessionButFinalUpdateStillProcessesItsOutcome() {
    var run = new AgentRunContext("active", "session-a", a.root(), a.id());
    turns.start(run, "verify Native");
    service.update("session-a", null, true);
    assertFalse(repo.current(a.id()).orElseThrow().processedRuns().contains("active"));
    turns.finish(run, ConversationTurnStore.Status.CANCELLED);
    service.update("session-a", "active");
    assertTrue(repo.current(a.id()).orElseThrow().processedRuns().contains("active"));
    assertEquals(1, repo.current(a.id()).orElseThrow().items().size());
  }

  /** 作業文脈の引き継ぎの中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void cancelledAssistantCompletionCannotCompleteWholeCurrentWork() {
    var run = new AgentRunContext("cancelled", "session-a", a.root(), a.id());
    turns.start(run, "implement everything");
    turns.finish(run, ConversationTurnStore.Status.CANCELLED, "everything done");
    doReturn(
            List.of(
                new WorkContextCandidate(
                    "ADD",
                    null,
                    Kind.CURRENT_WORK,
                    "implement everything",
                    "reported completion",
                    Status.COMPLETED,
                    List.of("cancelled:assistant"),
                    Origin.ASSISTANT)))
        .when(extractor)
        .extract(anyList(), anyList(), anyString());
    service.update("session-a", null);
    assertEquals(
        Status.UNCONFIRMED, repo.current(a.id()).orElseThrow().items().getFirst().status());
  }

  /** 作業文脈の引き継ぎで、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void aRunningRecordLeftByProcessExitDoesNotBlockANewerFinalizedRun() {
    var stranded = new AgentRunContext("stranded", "session-a", a.root(), a.id());
    turns.start(stranded, "old interrupted process");
    var newer = new AgentRunContext("newer", "session-a", a.root(), a.id());
    turns.start(newer, "new task");
    turns.finish(newer, ConversationTurnStore.Status.COMPLETED, "new task report");
    assertTrue(service.update("session-a", "newer").isPresent());
    assertTrue(repo.current(a.id()).orElseThrow().processedRuns().contains("newer"));
    assertFalse(repo.current(a.id()).orElseThrow().processedRuns().contains("stranded"));
  }
}
