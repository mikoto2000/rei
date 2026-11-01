package dev.mikoto2000.rei.durable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mikoto2000.rei.storage.StorageDatabase;
import dev.mikoto2000.rei.storage.StorageMigrationCoordinator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Snapshot completeness is decided by persisted enumeration, never a model assertion. */
@org.junit.jupiter.api.Tag("integration")
class TaskSnapshotTest {
  @TempDir Path temporary;

  DurableTaskStore store() throws Exception {
    try (var gate = new StorageMigrationCoordinator(temporary.resolve("data"))) {
      gate.prepare();
    }
    var database = new StorageDatabase(temporary.resolve("data"));
    return new DurableTaskStore(
        (write, work) -> write ? database.transaction(work::run) : database.read(work::run));
  }

  @Test
  void interruptedEnumerationCannotCompleteAndCapturedContentSurvivesSourceChange()
      throws Exception {
    var root = Files.createDirectory(temporary.resolve("repository"));
    Files.writeString(root.resolve("first.java"), "original");
    Files.writeString(root.resolve("second.java"), "second");
    var store = store();
    var scope = new DurableTaskStore.Scope("project", "session", root.toRealPath().toString());
    var task = store.begin(scope, null, "WORKTREE", "", List.of(), 20000, 10485760);
    assertThat(store.verify(scope, task.id()).complete()).isFalse();
    new SnapshotEnumerator(store).step(scope, task.id(), 1);
    assertThat(store.status(scope, task.id()).enumeration()).isEqualTo("SCANNING");
    store = store();
    var scanner = new SnapshotEnumerator(store);
    while (store.status(scope, task.id()).enumeration().equals("SCANNING"))
      scanner.step(scope, task.id(), 1);
    assertThat(store.status(scope, task.id()).enumeration()).isEqualTo("SEALED");
    Files.writeString(root.resolve("first.java"), "changed");
    assertThat(new String(store.content(scope, task.id(), "first.java"), java.nio.charset.StandardCharsets.UTF_8))
        .isEqualTo("original");
    assertThat(store.files(scope, task.id(), "", 10).entries()).hasSize(2);
    assertThat(store.verify(scope, task.id()).complete()).isFalse();
    var foreign = new DurableTaskStore.Scope("other", "session", scope.root());
    assertThatThrownBy(() -> store().status(foreign, task.id()))
        .hasMessageContaining("TASK_ACCESS_DENIED");
  }

  @Test
  void directoryMutationBeforeSavedCursorIsDetectedInsteadOfSilentlyOmittingFiles()
      throws Exception {
    var root = Files.createDirectory(temporary.resolve("repository"));
    Files.writeString(root.resolve("b.java"), "b");
    Files.writeString(root.resolve("z.java"), "z");
    var store = store();
    var scope = new DurableTaskStore.Scope("project", "session", root.toRealPath().toString());
    var task = store.begin(scope, null, "WORKTREE", "", List.of(), 100, 1024);
    var scanner = new SnapshotEnumerator(store);
    scanner.step(scope, task.id(), 1);
    Files.writeString(root.resolve("a.java"), "new file before cursor");
    // Set a distinct timestamp even on filesystems with coarse time resolution.
    Files.setLastModifiedTime(root, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 10000));
    scanner.step(scope, task.id(), 10);
    assertThat(store.status(scope, task.id()).enumeration()).isEqualTo("FAILED");
    assertThat(store.status(scope, task.id()).reason()).isEqualTo("DIRECTORY_CHANGED");
  }

  @Test
  void emptyTargetAndCapacityExhaustionNeverBecomeSuccessfulReviews() throws Exception {
    var root = Files.createDirectory(temporary.resolve("repository"));
    var store = store();
    var scope = new DurableTaskStore.Scope("project", "session", root.toRealPath().toString());
    var empty = store.begin(scope, null, "WORKTREE", "", List.of(), 1, 1024);
    new SnapshotEnumerator(store).step(scope, empty.id(), 10);
    assertThat(store.verify(scope, empty.id()).reason()).isEqualTo("EMPTY_TARGET_SET");
    Files.writeString(root.resolve("a.java"), "a");
    Files.writeString(root.resolve("b.java"), "b");
    var bounded = store.begin(scope, null, "WORKTREE", "", List.of(), 1, 1024);
    new SnapshotEnumerator(store).step(scope, bounded.id(), 10);
    assertThat(store.status(scope, bounded.id()).enumeration()).isEqualTo("FAILED");
    assertThat(store.verify(scope, bounded.id()).complete()).isFalse();
    assertThat(store.status(scope, bounded.id()).reason()).isEqualTo("FILE_LIMIT");
  }
}
