package dev.mikoto2000.rei.durable;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Fixed Git trees must remain readable when HEAD and the working tree change. */
class GitSnapshotTest {
  @TempDir Path temporary;

  @Test
  void commitContentIgnoresDirtyFilesAndExportIgnoreWhileWorktreeCapturesDirtyContent()
      throws Exception {
    var fixture = new TaskSnapshotTest();
    fixture.temporary = temporary;
    var store = fixture.store();
    var root = Files.createDirectory(temporary.resolve("repository"));
    git(root, "init");
    Files.writeString(root.resolve("a.java"), "committed");
    Files.writeString(root.resolve(".gitattributes"), "a.java export-ignore\n");
    git(root, "add", ".");
    git(root, "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "commit", "-m", "fixture");
    String commit = GitSnapshotSource.head(root);
    var scope = new DurableTaskStore.Scope("project", "session", root.toRealPath().toString());
    var fixed = store.begin(scope, null, "COMMIT", commit, List.of(), 100, 10240);
    Files.writeString(root.resolve("a.java"), "dirty");
    Files.writeString(root.resolve("untracked.java"), "untracked");
    var scan = new SnapshotEnumerator(store);
    while (store.status(scope, fixed.id()).enumeration().equals("SCANNING")) scan.step(scope, fixed.id(), 1);
    assertThat(store.status(scope, fixed.id()).enumeration()).isEqualTo("SEALED");
    assertThat(new String(store.content(scope, fixed.id(), "a.java"), StandardCharsets.UTF_8)).isEqualTo("committed");
    assertThat(store.files(scope, fixed.id(), "", 100).entries()).extracting(DurableTaskStore.FileEntry::path)
        .containsExactly(".gitattributes", "a.java");
    var live = store.begin(scope, null, "WORKTREE", commit, List.of(), 100, 10240);
    while (store.status(scope, live.id()).enumeration().equals("SCANNING")) scan.step(scope, live.id(), 10);
    assertThat(new String(store.content(scope, live.id(), "a.java"), StandardCharsets.UTF_8)).isEqualTo("dirty");
    assertThat(new String(store.content(scope, live.id(), "untracked.java"), StandardCharsets.UTF_8)).isEqualTo("untracked");
    assertThat(store.files(scope, live.id(), "", 100).entries()).anyMatch(e -> e.exclusionReason().equals("GIT_METADATA"));
  }

  private static void git(Path root, String... arguments) throws Exception {
    var command = new ArrayList<>(List.of("git"));
    command.addAll(List.of(arguments));
    var process = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertThat(process.waitFor()).withFailMessage(output).isZero();
  }
}
