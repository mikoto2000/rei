package dev.mikoto2000.rei.durable;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Large inventories and denied files must retain explicit coverage state. */
@org.junit.jupiter.api.Tag("integration")
class SnapshotScaleTest {
  @TempDir Path temporary;

  @Test
  void tenThousandFilesDeepDirectoriesAndPagedTargetsHaveNoMissingOrDuplicatePath()
      throws Exception {
    var fixture = new TaskSnapshotTest();
    fixture.temporary = temporary;
    var store = fixture.store();
    var root = Files.createDirectory(temporary.resolve("repository"));
    for (int i = 0; i < 10001; i++) Files.writeString(root.resolve(String.format("f%05d.java", i)), "class A {}");
    var deep = root;
    for (int i = 0; i < 30; i++) deep = Files.createDirectory(deep.resolve("d"));
    Files.writeString(deep.resolve("deep.java"), "deep");
    Files.createDirectory(root.resolve("excluded"));
    Files.writeString(root.resolve("excluded/hidden.java"), "excluded");
    var scope = new DurableTaskStore.Scope("project", "session", root.toRealPath().toString());
    var task = store.begin(scope, null, "WORKTREE", "", List.of("excluded/**"), 20000, 10485760);
    int batches = 0;
    while (store.status(scope, task.id()).enumeration().equals("SCANNING") && batches++ < 150)
      new SnapshotEnumerator(store).step(scope, task.id(), 257);
    assertThat(store.status(scope, task.id()).enumeration()).isEqualTo("SEALED");
    assertThat(store.status(scope, task.id()).files()).isEqualTo(10002);
    assertThat(store.status(scope, task.id()).excluded()).isEqualTo(1);
    var paths = new HashSet<String>();
    String cursor = "";
    do {
      var page = store.files(scope, task.id(), cursor, 97);
      for (var entry : page.entries()) assertThat(paths.add(entry.path())).isTrue();
      cursor = page.nextCursor();
    } while (cursor != null);
    assertThat(paths).hasSize(10003);
  }

  @Test
  void accessDeniedIsSavedAndCannotBeSilentlyExcluded() throws Exception {
    var fixture = new TaskSnapshotTest();
    fixture.temporary = temporary;
    var store = fixture.store();
    var root = Files.createDirectory(temporary.resolve("repository"));
    Files.writeString(root.resolve("denied.java"), "denied");
    var scope = new DurableTaskStore.Scope("project", "session", root.toRealPath().toString());
    var task = store.begin(scope, null, "WORKTREE", "", List.of(), 100, 1024);
    new SnapshotEnumerator(store, path -> { throw new AccessDeniedException(path.toString()); })
        .step(scope, task.id(), 10);
    assertThat(store.status(scope, task.id()).errors()).isEqualTo(1);
    assertThat(store.status(scope, task.id()).excluded()).isZero();
    assertThat(store.files(scope, task.id(), "", 10).entries().getFirst().error()).isEqualTo("ACCESS_DENIED");
    assertThat(store.verify(scope, task.id()).complete()).isFalse();
  }
}
