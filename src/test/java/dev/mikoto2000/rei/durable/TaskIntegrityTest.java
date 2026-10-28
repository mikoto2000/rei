package dev.mikoto2000.rei.durable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TaskIntegrityTest {
  @TempDir Path temporary;

  @Test
  void independentCompletionAndRecoveryRejectCorruptCommittedBody() throws Exception {
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
    work.commit(scope, claim, "checked", List.of());
    store.write(db -> DurableTaskStore.execute(db, "UPDATE durable_results SET body='corrupt' WHERE work=?", claim.item().id()));
    assertThat(store.verify(scope, task.id()).complete()).isFalse();
    assertThat(store.verify(scope, task.id()).reason()).isEqualTo("RESULT_CORRUPT");
    assertThatThrownBy(() -> work.resume(scope, task.id(), "next-run")).hasMessageContaining("RESULT_CORRUPT");
    String current = store.read(db -> {
      try (var query = DurableTaskStore.statement(db, "SELECT current_run FROM durable_tasks WHERE id=?", task.id()); var row = query.executeQuery()) {
        row.next(); return row.getString(1);
      }
    });
    assertThat(current).isEqualTo("run");
  }
}
