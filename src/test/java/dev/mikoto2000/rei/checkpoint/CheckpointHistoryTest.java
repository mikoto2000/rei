package dev.mikoto2000.rei.checkpoint;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.sqlite.SQLiteDataSource;

@Tag("integration")
class CheckpointHistoryTest {
  @TempDir Path root;
  SQLiteDataSource source;
  CheckpointProperties settings;
  PersistentCheckpointRepository repo;

  @BeforeEach
  void setup() {
    source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + root.resolve("history.db"));
    settings = new CheckpointProperties();
    repo = new PersistentCheckpointRepository(source, settings);
  }

  PersistentCheckpoint initial(String task) {
    return PersistentCheckpoint.initial(
        task, "project", "session", "run-" + task, root, "request-" + task);
  }

  PersistentCheckpoint save(String task, int revisions, String status) {
    var state = initial(task);
    var fields = repo.fields(state);
    fields.put("status", status);
    state = repo.fields(fields);
    for (int i = 0; i < revisions; i++) state = repo.save(state, i, task + "-" + (i + 1));
    return state;
  }

  void sql(String query) throws Exception {
    try (var db = DriverManager.getConnection(source.getUrl());
        var statement = db.createStatement()) {
      statement.executeUpdate(query);
    }
  }

  long revisionRows(String task) throws Exception {
    try (var db = DriverManager.getConnection(source.getUrl());
        var query = db.prepareStatement("SELECT COUNT(*) FROM checkpoint_revisions WHERE task=?")) {
      query.setString(1, task);
      try (var rows = query.executeQuery()) {
        assertTrue(rows.next());
        return rows.getLong(1);
      }
    }
  }

  @Test
  void defaultStorageBudgetIs128MiBWhileOtherLimitsAreUnchanged() {
    assertEquals(134217728L, settings.getMaxBytes());
    assertEquals(10000, settings.getMaxRevisions());
    assertEquals(262144, settings.getMaxSnapshotBytes());
  }

  @Test
  void automaticallyRemovesOldestAcrossTasksOnlyUntilNewSaveFitsAndRetainsDeduplication()
      throws Exception {
    save("older", 2, "FAILED");
    save("newer", 2, "INTERRUPTED");
    // Same-second timestamps with and without fractional seconds must be ordered chronologically.
    sql(
        "UPDATE checkpoint_revisions SET created='2026-10-12T00:00:00Z' WHERE task='older' AND revision=1");
    sql(
        "UPDATE checkpoint_revisions SET created='2026-10-12T00:00:00.100Z' WHERE task='newer' AND revision=1");
    settings.setMaxRevisions(4);
    repo.save(initial("incoming"), 0, "incoming-1");
    assertEquals(1, revisionRows("older"));
    assertEquals(2, revisionRows("newer"));
    assertEquals(2, repo.get("project", "older").revision());
    assertEquals(4, repo.historyUsage().revisions());
    assertEquals(5, repo.historyUsage().eventKeys());
    var reopened = new PersistentCheckpointRepository(source, settings);
    assertEquals(2, reopened.save(initial("older"), 0, "older-1").revision());
    assertEquals(4, reopened.historyUsage().revisions());
  }

  @Test
  void automaticallyMakesRoomForSnapshotBytes() throws Exception {
    var fields = repo.fields(initial("large"));
    fields.put("request", "x".repeat(4000));
    var state = repo.fields(fields);
    repo.save(state, 0, "large-1");
    repo.save(state, 1, "large-2");
    long before = repo.historyUsage().snapshotBytes();
    settings.setMaxBytes(before + 256);
    repo.save(initial("incoming"), 0, "incoming");
    assertEquals(1, revisionRows("large"));
    assertEquals(2, repo.get("project", "large").revision());
    assertTrue(repo.historyUsage().snapshotBytes() <= settings.getMaxBytes());
  }

  @Test
  void previewAndApplyKeepOnlyHeadsAndValidFallbackEvenForLiveAndWaitingTasks() throws Exception {
    save("ordinary", 3, "FAILED");
    save("live", 3, "RUNNING");
    save("waiting", 3, "WAITING_APPROVAL");
    save("damaged", 3, "FAILED");
    assertTrue(repo.acquire("project", "live", "running"));
    sql("UPDATE checkpoint_revisions SET snapshot='broken' WHERE task='damaged' AND revision=3");
    var preview = repo.cleanupHistory(false);
    assertFalse(preview.applied());
    assertEquals(7, preview.removedRevisions());
    assertEquals(4, preview.protectedTasks());
    assertEquals(5, preview.protectedRevisions());
    assertEquals(
        preview.before().snapshotBytes() - preview.removedBytes(), preview.protectedBytes());
    assertEquals(12, repo.historyUsage().revisions());
    assertEquals(5, preview.after().revisions());
    var applied = repo.cleanupHistory(true);
    assertTrue(applied.applied());
    assertEquals(preview.removedBytes(), applied.removedBytes());
    assertEquals(5, repo.historyUsage().revisions());
    assertEquals(12, repo.historyUsage().eventKeys());
    assertEquals(3, repo.get("project", "ordinary").revision());
    assertEquals(1, revisionRows("live"));
    assertEquals(1, revisionRows("waiting"));
    assertEquals(2, revisionRows("damaged"));
    assertTrue(repo.leased("project", "live"));
    assertFalse(repo.acquire("project", "live", "other"));
    assertEquals(2, repo.get("project", "damaged").revision());
    assertFalse(repo.diagnostics("project", "damaged").isEmpty());
  }

  @Test
  void acquiringLeaseAfterPreviewDoesNotPinUnneededHistoryOrReleaseOwnership() throws Exception {
    save("task", 3, "FAILED");
    assertEquals(2, repo.cleanupHistory(false).removedRevisions());
    assertTrue(repo.acquire("project", "task", "resume"));
    assertEquals(2, repo.cleanupHistory(true).removedRevisions());
    assertEquals(1, revisionRows("task"));
    assertTrue(repo.leased("project", "task"));
    assertEquals(3, repo.get("project", "task").revision());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"WAITING_APPROVAL", "QUEUED", "RESUMING", "DENY_QUEUED", "UNKNOWN", "EXPIRED"})
  void approvalContinuationsProtectOneHistoricalRunAnchorWithoutPageLimit(String status)
      throws Exception {
    var first = save("approval", 2, "COMPLETED");
    var fields = repo.fields(first);
    fields.put("runId", "new-run");
    repo.save(repo.fields(fields), 2, "approval-3");
    sql("CREATE TABLE approval_continuations(approval TEXT PRIMARY KEY,run TEXT,state TEXT)");
    for (int i = 0; i < 300; i++)
      sql(
          "INSERT INTO approval_continuations VALUES('unrelated-"
              + i
              + "','run-unrelated-"
              + i
              + "','QUEUED')");
    sql("INSERT INTO approval_continuations VALUES('protected','run-approval','" + status + "')");
    var cleanup = repo.cleanupHistory(true);
    assertEquals(1, cleanup.removedRevisions());
    assertEquals(2, revisionRows("approval"));
    assertEquals(2, cleanup.protectedRevisions());
    assertTrue(hasRevision("project", "approval", 2));
    assertTrue(repo.findByRun("project", "run-approval").isPresent());
  }

  @Test
  void insufficientSpaceRollsBackTentativeDeletionsAndReportsRealUsage() throws Exception {
    save("old", 2, "FAILED");
    var anchor = save("protected", 1, "WAITING_APPROVAL");
    var fields = repo.fields(anchor.resume("resumed-protected"));
    fields.put("status", "WAITING_APPROVAL");
    repo.save(repo.fields(fields), 1, "protected-2");
    var before = repo.historyUsage();
    settings.setMaxRevisions(3);
    var error = assertThrows(CheckpointException.class, () -> repo.save(initial("new"), 0, "new"));
    assertEquals(CheckpointException.Code.CAPACITY, error.code());
    assertEquals(4, repo.historyUsage().revisions());
    assertEquals(before.snapshotBytes(), repo.historyUsage().snapshotBytes());
    assertEquals(2, revisionRows("old"));
    assertEquals(2, revisionRows("protected"));
    assertTrue(error.getMessage().contains("4"));
    assertTrue(error.getMessage().contains("rei.checkpoint.max-revisions"));
    assertEquals(3, error.capacityDetail().protectedRevisions());
    assertEquals(
        repo.cleanupHistory(false).protectedBytes(), error.capacityDetail().protectedBytes());
    assertTrue(error.getMessage().contains("保護されて削除できない履歴: 3 件"));
  }

  @Test
  void liveTaskMakesRoomByBytesWhileKeepingLeaseAndDedupKeys() throws Exception {
    var fields = repo.fields(initial("live"));
    fields.put("request", "x".repeat(8000));
    var state = repo.save(repo.fields(fields), 0, "live-1");
    long snapshotBytes = repo.historyUsage().snapshotBytes();
    settings.setMaxBytes(snapshotBytes * 3 + 256);
    assertTrue(repo.acquire("project", "live", "run-live"));
    for (int revision = 2; revision <= 40; revision++)
      state = repo.save(state, revision - 1, "live-" + revision);
    assertEquals(40, state.revision());
    assertEquals(3, revisionRows("live"));
    assertFalse(hasRevision("project", "live", 37));
    assertTrue(hasRevision("project", "live", 38));
    assertTrue(repo.historyUsage().snapshotBytes() <= settings.getMaxBytes());
    assertTrue(repo.leased("project", "live"));
    assertEquals(40, repo.historyUsage().eventKeys());
    assertEquals(40, repo.save(initial("live"), 0, "live-1").revision());
  }

  @Test
  void incomingResumePinsItsDirectSourceButReleasesGrandparentOnNextResume() throws Exception {
    var state = save("task", 2, "INTERRUPTED");
    settings.setMaxRevisions(2);
    state = repo.save(state.resume("second-run"), 2, "resume-second");
    assertEquals(2, revisionRows("task"));
    assertTrue(hasRevision("project", "task", 2));
    for (int revision = 4; revision <= 10; revision++)
      state = repo.save(state, revision - 1, "progress-" + revision);
    assertTrue(hasRevision("project", "task", 2));
    assertTrue(hasRevision("project", "task", 10));
    state = repo.save(state.resume("third-run"), 10, "resume-third");
    assertEquals(11, state.revision());
    assertEquals(10, state.resumedFromRevision());
    assertFalse(hasRevision("project", "task", 2));
    assertTrue(hasRevision("project", "task", 10));
    assertEquals(2, repo.cleanupHistory(false).protectedRevisions());
  }

  @Test
  void replacementWithOneRevisionLimitIsAtomicIncludingInsertFailure() throws Exception {
    var state = save("task", 1, "RUNNING");
    settings.setMaxRevisions(1);
    assertTrue(repo.acquire("project", "task", "run-task"));
    state = repo.save(state, 1, "second");
    assertEquals(2, state.revision());
    assertEquals(1, revisionRows("task"));
    sql(
        "CREATE TRIGGER fail_replace BEFORE INSERT ON checkpoint_revisions WHEN NEW.revision=3 BEGIN SELECT RAISE(ABORT,'injected'); END");
    var current = state;
    assertThrows(RuntimeException.class, () -> repo.save(current, 2, "third"));
    assertEquals(2, repo.get("project", "task").revision());
    assertEquals(1, revisionRows("task"));
    assertTrue(repo.leased("project", "task"));
    assertEquals(2, repo.historyUsage().eventKeys());
  }

  @ParameterizedTest
  @ValueSource(strings = {"COMPLETED", "CANCELLED", "DENIED"})
  void completedApprovalReceiptDoesNotPinOldRun(String status) throws Exception {
    var old = save("approval", 2, "COMPLETED");
    var fields = repo.fields(old);
    fields.put("runId", "new-run");
    repo.save(repo.fields(fields), 2, "new-run");
    sql("CREATE TABLE approval_continuations(approval TEXT PRIMARY KEY,run TEXT,state TEXT)");
    sql("INSERT INTO approval_continuations VALUES('done','run-approval','" + status + "')");
    assertEquals(2, repo.cleanupHistory(true).removedRevisions());
    assertEquals(1, revisionRows("approval"));
  }

  @Test
  void realApprovalDocumentPinsExactRunAndOwnerIncludingPathUriSerialization() throws Exception {
    var old = save("approval", 2, "COMPLETED");
    var fields = repo.fields(old);
    fields.put("runId", "new-run");
    repo.save(repo.fields(fields), 2, "new-run");
    var unrelated =
        PersistentCheckpoint.initial(
            "other", "other-project", "session", "run-approval", root, "request");
    unrelated = repo.save(unrelated, 0, "other-1");
    unrelated = repo.save(unrelated, 1, "other-2");
    fields = repo.fields(unrelated);
    fields.put("runId", "other-new-run");
    repo.save(repo.fields(fields), 2, "other-3");
    var store = new dev.mikoto2000.rei.core.policy.ApprovalContinuationStore(source);
    var owner =
        new dev.mikoto2000.rei.core.chat.AgentRunContext(
            "run-approval", "session", root, "project");
    var call =
        new org.springframework.ai.chat.messages.AssistantMessage.ToolCall(
            "call", "function", "runCommand", "{}");
    store.save(
        "approval",
        owner,
        "request",
        List.of(),
        new dev.mikoto2000.rei.core.chat.RunContinuation.PendingTools("", List.of(call), List.of()),
        new dev.mikoto2000.rei.llm.OutputLimitRunBudget(2, 20).snapshot(),
        false);
    String receipt = store.find("approval").orElseThrow().document();
    var cleanup = repo.cleanupHistory(true);
    assertEquals(3, cleanup.removedRevisions());
    assertEquals(3, cleanup.protectedRevisions());
    assertTrue(hasRevision("project", "approval", 2));
    assertTrue(hasRevision("project", "approval", 3));
    assertFalse(hasRevision("other-project", "other", 2));
    assertTrue(hasRevision("other-project", "other", 3));
    assertEquals(receipt, store.find("approval").orElseThrow().document());
    assertEquals(
        "call",
        store.saved(store.find("approval").orElseThrow()).pendingTools().calls().getFirst().id());
  }

  @Test
  void healthyHeadDoesNotPinAllHistoricRunsByOriginalRunIdentity() throws Exception {
    var state = save("approval", 3, "COMPLETED");
    var fields = repo.fields(state);
    fields.put("runId", "new-run");
    state = repo.save(repo.fields(fields), 3, "new-run");
    state = repo.save(state, 4, "new-progress");
    sql("CREATE TABLE approval_continuations(approval TEXT PRIMARY KEY,run TEXT,state TEXT)");
    sql("INSERT INTO approval_continuations VALUES('approval','run-approval','UNKNOWN')");
    var cleanup = repo.cleanupHistory(true);
    assertEquals(3, cleanup.removedRevisions());
    assertTrue(hasRevision("project", "approval", 3));
    assertTrue(hasRevision("project", "approval", 5));
    assertEquals(2, revisionRows("approval"));
  }

  boolean hasRevision(String project, String task, long revision) throws Exception {
    try (var db = DriverManager.getConnection(source.getUrl());
        var query =
            db.prepareStatement(
                "SELECT COUNT(*) FROM checkpoint_revisions WHERE project=? AND task=? AND revision=?")) {
      query.setString(1, project);
      query.setString(2, task);
      query.setLong(3, revision);
      try (var rows = query.executeQuery()) {
        assertTrue(rows.next());
        return rows.getLong(1) == 1;
      }
    }
  }

  @Test
  void failedInsertRollsBackAutomaticCleanup() throws Exception {
    save("old", 2, "FAILED");
    settings.setMaxRevisions(2);
    sql(
        "CREATE TRIGGER fail_new BEFORE INSERT ON checkpoint_revisions WHEN NEW.task='new' BEGIN SELECT RAISE(ABORT,'injected'); END");
    assertThrows(RuntimeException.class, () -> repo.save(initial("new"), 0, "new"));
    assertEquals(2, revisionRows("old"));
    assertEquals(2, repo.historyUsage().eventKeys());
  }

  @Test
  void staleSaveNeverDeletesOtherTasksHistory() throws Exception {
    save("task", 2, "FAILED");
    save("other", 2, "FAILED");
    settings.setMaxRevisions(4);
    assertThrows(
        ConcurrentModificationException.class, () -> repo.save(initial("task"), 1, "stale"));
    assertEquals(4, repo.historyUsage().revisions());
  }

  @Test
  void cleanupAndConcurrentSaveRetainHeadAndMonotonicRevision() throws Exception {
    save("task", 3, "INTERRUPTED");
    var barrier = new java.util.concurrent.CyclicBarrier(2);
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var cleanup =
          pool.submit(
              () -> {
                barrier.await();
                return new PersistentCheckpointRepository(source, settings).cleanupHistory(true);
              });
      var save =
          pool.submit(
              () -> {
                barrier.await();
                return repo.save(repo.get("project", "task"), 3, "concurrent");
              });
      cleanup.get();
      assertEquals(4, save.get().revision());
    }
    assertEquals(4, repo.get("project", "task").revision());
    assertEquals(4, repo.historyUsage().eventKeys());
    assertTrue(repo.historyUsage().revisions() >= 1);
  }
}
