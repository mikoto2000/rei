package dev.mikoto2000.rei.durable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@org.junit.jupiter.api.Tag("integration")
class WorkItemRecoveryTest {
  @TempDir Path temporary;

  @Test
  void stableTaskRecoversWithoutCheckpointAfter101RunsAndNeverReexecutesCompletedWork() throws Exception {
    var fixture = new TaskSnapshotTest();
    fixture.temporary = temporary;
    var store = fixture.store();
    var root = Files.createDirectory(temporary.resolve("repository"));
    Files.writeString(root.resolve("a.java"), "a");
    Files.writeString(root.resolve("b.java"), "b");
    var scope = new DurableTaskStore.Scope("project", "session", root.toString());
    var task = store.beginForRun(scope, "run0", null, "WORKTREE", "", List.of(), 100, 1024);
    new SnapshotEnumerator(store).step(scope, task.id(), 100);
    var work = new WorkItemStore(store);
    var first = work.claim(scope, task.id(), "run0");
    work.delivered(scope, first, WorkItemStore.hash("a".getBytes(StandardCharsets.UTF_8)));
    var saved = work.commit(scope, first, "checked", List.of());
    var abandoned = work.claim(scope, task.id(), "run0");
    for (int i = 1; i <= 101; i++) {
      store = fixture.store();
      work = new WorkItemStore(store);
      work.resume(scope, task.id(), "run" + i);
      work.resume(scope, task.id(), "run" + i); // lost acknowledgement is harmless
      assertThat(store.tasks(scope, null, 10).entries()).extracting(DurableTaskStore.Task::id).containsExactly(task.id());
      var next = work.claim(scope, task.id(), "run" + i);
      assertThat(next.item().id()).isEqualTo(abandoned.item().id());
      assertThat(work.results(scope, task.id(), null, 10).entries()).containsExactly(saved);
      if (i == 101) {
        var finalWork = work;
        assertThatThrownBy(() -> finalWork.commit(scope, abandoned, "stale", List.of())).hasMessageContaining("TASK_RUN_NOT_BOUND");
        work.delivered(scope, next, WorkItemStore.hash("b".getBytes(StandardCharsets.UTF_8)));
        work.commit(scope, next, "checked", List.of());
      }
    }
    assertThat(store.verify(scope, task.id()).complete()).isTrue();
    long attempts = store.read(db -> DurableTaskStore.number(db, "SELECT COUNT(*) FROM durable_attempts WHERE task=?", task.id()));
    assertThat(attempts).isEqualTo(102L);
  }

  @Test
  void resultAndCompletionRollbackAtEverySqlBoundaryAndValidOrphanIsRecovered() throws Exception {
    var fixture = new TaskSnapshotTest();
    fixture.temporary = temporary;
    var store = fixture.store();
    var root = Files.createDirectory(temporary.resolve("repository"));
    Files.writeString(root.resolve("a.java"), "a");
    var scope = new DurableTaskStore.Scope("project", "session", root.toString());
    var task = store.beginForRun(scope, "run", null, "WORKTREE", "", List.of(), 100, 1024);
    new SnapshotEnumerator(store).step(scope, task.id(), 100);
    var work = new WorkItemStore(store);
    var claim = work.claim(scope, task.id(), "run");
    work.delivered(scope, claim, WorkItemStore.hash("a".getBytes(StandardCharsets.UTF_8)));
    for (var boundary : List.of("BEFORE INSERT ON durable_results", "AFTER INSERT ON durable_results",
        "BEFORE UPDATE OF state ON durable_work", "AFTER UPDATE OF state ON durable_work")) {
      store.write(db -> { try (var sql = db.createStatement()) {
        sql.execute("CREATE TRIGGER fault " + boundary + " BEGIN SELECT RAISE(ABORT,'injected'); END");
      } return null; });
      assertThatThrownBy(() -> work.commit(scope, claim, "checked", List.of())).hasStackTraceContaining("injected");
      assertThat(work.results(scope, task.id(), null, 10).entries()).isEmpty();
      String state = store.read(db -> WorkItemStore.item(db, claim.item().id()).state());
      assertThat(state).isEqualTo("RUNNING");
      store.write(db -> {
        try (var sql = db.createStatement()) { sql.execute("DROP TRIGGER fault"); }
        return null;
      });
    }
    var saved = work.commit(scope, claim, "checked", List.of());
    // Simulate a pre-existing result saved by an older, non-atomic writer.
    store.write(db -> {
      DurableTaskStore.execute(db, "UPDATE durable_tasks SET state='ACTIVE' WHERE id=?", task.id());
      DurableTaskStore.execute(db, "UPDATE durable_work SET state='RUNNING',result_id=NULL WHERE id=?", claim.item().id());
      return null;
    });
    var reopened = new WorkItemStore(fixture.store());
    reopened.resume(scope, task.id(), "recovered");
    assertThat(reopened.results(scope, task.id(), null, 10).entries()).containsExactly(saved);
    assertThat(store.verify(scope, task.id()).complete()).isTrue();
    assertThat(store.status(scope, task.id()).state()).isEqualTo("COMPLETE");
  }
}
