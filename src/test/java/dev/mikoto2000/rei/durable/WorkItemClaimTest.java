package dev.mikoto2000.rei.durable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkItemClaimTest {
  @TempDir Path temporary;

  @Test
  void onePendingItemHasOneClaimAndReadsFrozenSourceInAnotherStore() throws Exception {
    var fixture = new TaskSnapshotTest();
    fixture.temporary = temporary;
    var store = fixture.store();
    var root = Files.createDirectory(temporary.resolve("repository"));
    Files.writeString(root.resolve("a.java"), "original🙂");
    var scope = new DurableTaskStore.Scope("project", "session", root.toString());
    var task = store.beginForRun(scope, "run", null, "WORKTREE", "", List.of(), 100, 1024);
    new SnapshotEnumerator(store).step(scope, task.id(), 100);
    var work = new WorkItemStore(store);
    var claim = work.claim(scope, task.id(), "run");
    assertThat(claim.item().path()).isEqualTo("a.java");
    assertThat(work.claim(scope, task.id(), "run")).isNull();
    Files.writeString(root.resolve("a.java"), "changed");
    assertThat(new WorkItemStore(fixture.store()).source(scope, claim.item())).isEqualTo("original🙂");
    assertThatThrownBy(() -> work.claim(new DurableTaskStore.Scope("other", "session", root.toString()), task.id(), "run"))
        .hasMessageContaining("TASK_ACCESS_DENIED");
    assertThatThrownBy(() -> work.claim(scope, task.id(), "unbound-run"))
        .hasMessageContaining("TASK_RUN_NOT_BOUND");
  }
}
