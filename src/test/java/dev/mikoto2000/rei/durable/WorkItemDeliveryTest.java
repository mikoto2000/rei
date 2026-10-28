package dev.mikoto2000.rei.durable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkItemDeliveryTest {
  @TempDir Path temporary;

  @Test
  void onlyExactFrozenRangeCanBeConfirmedByTheCurrentClaim() throws Exception {
    var fixture = new TaskSnapshotTest();
    fixture.temporary = temporary;
    var store = fixture.store();
    var root = Files.createDirectory(temporary.resolve("repository"));
    Files.writeString(root.resolve("a.java"), "abcdef");
    var scope = new DurableTaskStore.Scope("project", "session", root.toString());
    var task = store.beginForRun(scope, "run", null, "WORKTREE", "", List.of(), 100, 1024);
    new SnapshotEnumerator(store).step(scope, task.id(), 100);
    var work = new WorkItemStore(store);
    var claim = work.claim(scope, task.id(), "run");
    assertThatThrownBy(() -> work.delivered(scope, claim, "0".repeat(64))).hasMessageContaining("DELIVERY_RANGE_MISMATCH");
    assertThat(work.deliveredHash(scope, claim)).isEmpty();
    String hash = WorkItemStore.hash("abcdef".getBytes(StandardCharsets.UTF_8));
    work.delivered(scope, claim, hash);
    assertThat(new WorkItemStore(fixture.store()).deliveredHash(scope, claim)).isEqualTo(hash);
    assertThatThrownBy(() -> work.delivered(scope, new WorkItemStore.Claim(claim.item(), "other", claim.attempt()), hash))
        .hasMessageContaining("TASK_RUN_NOT_BOUND");
    var item = claim.item();
    var forged = new WorkItemStore.Item(item.id(), item.task(), item.snapshot(), item.path(), item.start(), item.end(),
        item.version(), item.kind(), "different criterion", item.state(), item.required());
    assertThatThrownBy(() -> work.commit(scope, new WorkItemStore.Claim(forged, claim.run(), claim.attempt()), "checked", List.of()))
        .hasMessageContaining("WORK_ITEM_CHANGED");
    store.write(db -> { DurableTaskStore.execute(db, "UPDATE durable_files SET text='tampered' WHERE snapshot=?", task.snapshot()); return null; });
    assertThatThrownBy(() -> work.source(scope, claim.item())).hasMessageContaining("SNAPSHOT_CORRUPT");
  }
}
