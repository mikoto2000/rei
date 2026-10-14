package dev.mikoto2000.rei.checkpoint;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.RunContinuation;
import dev.mikoto2000.rei.core.policy.ApprovalContinuationStore;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;
import dev.mikoto2000.rei.storage.StorageObjectRegistry;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ConcurrentModificationException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.sqlite.SQLiteDataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@Tag("integration")
class CheckpointForceCleanupTest {
  @TempDir Path root;
  private SQLiteDataSource source;
  private PersistentCheckpointRepository repo;

  @BeforeEach
  void setup() {
    source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + root.resolve("force-cleanup.db"));
    repo = new PersistentCheckpointRepository(source, new CheckpointProperties());
  }

  @Test
  void forcePreviewIncludesProtectedDamagedAndOrphanHistoryWithoutMutation() throws Exception {
    createMixedHistory();
    var before = repo.historyUsage();
    long heads = count("checkpoint_heads");
    var registry = mock(StorageObjectRegistry.class);
    repo.setStorageObjectRegistry(registry);

    var preview = repo.cleanupHistory(false, true);

    assertTrue(preview.force());
    assertFalse(preview.applied());
    assertEquals(before.revisions(), preview.removedRevisions());
    assertEquals(before.snapshotBytes(), preview.removedBytes());
    assertEquals(0, preview.protectedTasks());
    assertEquals(0, preview.protectedRevisions());
    assertEquals(0, preview.protectedBytes());
    assertEquals(before, preview.before());
    assertEquals(0, preview.after().revisions());
    assertEquals(0, preview.after().snapshotBytes());
    assertEquals(before.eventKeys(), preview.after().eventKeys());
    assertEquals(before, repo.historyUsage());
    assertEquals(heads, count("checkpoint_heads"));
    assertTrue(repo.leased("project", "live"));
    assertEquals(2, repo.get("project", "resumed").resumedFromRevision());
    verifyNoInteractions(registry);
  }

  @Test
  void forceApplyDeletesAllCheckpointStateButRetainsExactApprovalReceiptAndOtherTables() throws Exception {
    createMixedHistory();
    var store = new ApprovalContinuationStore(source);
    var owner = new AgentRunContext("run-waiting", "session", root, "project");
    var call = new AssistantMessage.ToolCall("exact-call", "function", "runCommand",
        "{\"request\":{\"command\":\"git status --short\"}}");
    store.save("approval", owner, "request", List.of(),
        new RunContinuation.PendingTools("", List.of(call), List.of()),
        new OutputLimitRunBudget(2, 20).snapshot(), false);
    var receipt = store.find("approval").orElseThrow();
    execute("CREATE TABLE unrelated_data(id TEXT PRIMARY KEY, value TEXT)");
    execute("INSERT INTO unrelated_data VALUES('keep','unchanged')");
    var before = repo.historyUsage();

    var result = repo.cleanupHistory(true, true);

    assertTrue(result.force());
    assertTrue(result.applied());
    assertEquals(before.revisions(), result.removedRevisions());
    assertEquals(before.snapshotBytes(), result.removedBytes());
    assertEquals(0, result.protectedRevisions());
    assertEquals(0, result.protectedBytes());
    assertEquals(0, count("checkpoint_revisions"));
    assertEquals(0, count("checkpoint_heads"));
    assertEquals(before.eventKeys(), count("checkpoint_event_keys"));
    assertEquals(1, count("unrelated_data"));
    assertEquals(receipt, store.find("approval").orElseThrow());
    assertEquals(call, store.saved(receipt).pendingTools().calls().getFirst());
    assertThrows(IllegalArgumentException.class, () -> repo.get("project", "waiting"));
    assertTrue(repo.list("project").isEmpty());
    assertFalse(repo.leased("project", "live"));

    var repeated = repo.cleanupHistory(true, true);
    assertTrue(repeated.force());
    assertEquals(0, repeated.removedRevisions());
    assertEquals(0, repeated.removedBytes());
    assertEquals(result.after(), repeated.before());
    assertEquals(before.eventKeys(), repo.historyUsage().eventKeys());
  }

  @Test
  void storageReferencesAreReleasedOnlyAfterAllCheckpointDeletesCommit() throws Exception {
    save("first", 2, "RUNNING");
    save("second", 1, "WAITING_APPROVAL");
    var registry = mock(StorageObjectRegistry.class);
    repo.setStorageObjectRegistry(registry);
    Set<String> expected = Set.of("project:first:1", "project:first:2", "project:second:1");
    doAnswer(invocation -> {
      assertEquals(0, count("checkpoint_revisions"));
      assertEquals(0, count("checkpoint_heads"));
      List<String> owners = invocation.getArgument(0);
      assertEquals(expected, new HashSet<>(owners));
      return null;
    }).when(registry).releaseCheckpointHistory(anyList());

    repo.cleanupHistory(true, true);

    verify(registry).releaseCheckpointHistory(anyList());
  }

  @Test
  void failureDeletingHeadsRollsBackSnapshotsHeadsAndStorageReferences() throws Exception {
    createMixedHistory();
    var before = repo.historyUsage();
    long heads = count("checkpoint_heads");
    var registry = mock(StorageObjectRegistry.class);
    repo.setStorageObjectRegistry(registry);
    execute("CREATE TRIGGER fail_force BEFORE DELETE ON checkpoint_heads "
        + "WHEN OLD.task='live' BEGIN SELECT RAISE(ABORT,'injected failure'); END");

    assertThrows(RuntimeException.class, () -> repo.cleanupHistory(true, true));

    assertEquals(before, repo.historyUsage());
    assertEquals(heads, count("checkpoint_heads"));
    assertEquals(3, repo.get("project", "resumed").revision());
    assertTrue(repo.leased("project", "live"));
    verifyNoInteractions(registry);
  }

  @Test
  void lateSaveCannotResurrectDeletedTaskButNewTaskCanStart() throws Exception {
    var stale = save("old", 2, "RUNNING");
    repo.cleanupHistory(true, true);

    assertThrows(ConcurrentModificationException.class, () -> repo.save(stale, 2, "late-save"));
    assertEquals(0, count("checkpoint_heads"));
    assertEquals(0, count("checkpoint_revisions"));
    assertEquals(2, count("checkpoint_event_keys"));

    var fresh = repo.save(initial("new"), 0, "new-1");
    assertEquals(1, fresh.revision());
    assertEquals(1, repo.historyUsage().revisions());
    assertEquals(3, repo.historyUsage().eventKeys());
    assertEquals(fresh, repo.get("project", "new"));
  }

  private void createMixedHistory() throws Exception {
    save("ordinary", 2, "FAILED");
    save("live", 2, "RUNNING");
    assertTrue(repo.acquire("project", "live", "run-live"));
    save("waiting", 2, "WAITING_APPROVAL");
    var resumed = save("resumed", 2, "INTERRUPTED");
    repo.save(resumed.resume("resumed-run"), 2, "resumed-3");
    save("damaged", 2, "FAILED");
    execute("UPDATE checkpoint_revisions SET snapshot='broken' WHERE task='damaged' AND revision=2");
    execute("INSERT INTO checkpoint_revisions VALUES('other-project','orphan',7,'orphan-event',"
        + "'orphan snapshot','2026-10-15T00:00:00Z')");
    execute("INSERT INTO checkpoint_heads(project,task,revision) VALUES('project','empty-head',0)");
  }

  private PersistentCheckpoint initial(String task) {
    return PersistentCheckpoint.initial(task, "project", "session", "run-" + task, root, "request-" + task);
  }

  private PersistentCheckpoint save(String task, int revisions, String status) {
    var fields = repo.fields(initial(task));
    fields.put("status", status);
    var state = repo.fields(fields);
    for (int revision = 1; revision <= revisions; revision++) {
      state = repo.save(state, revision - 1, task + "-" + revision);
    }
    return state;
  }

  private void execute(String sql) throws Exception {
    try (var connection = DriverManager.getConnection(source.getUrl());
        var statement = connection.createStatement()) {
      statement.executeUpdate(sql);
    }
  }

  private long count(String table) throws Exception {
    try (var connection = DriverManager.getConnection(source.getUrl());
        var statement = connection.createStatement();
        var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
      assertTrue(rows.next());
      return rows.getLong(1);
    }
  }
}
