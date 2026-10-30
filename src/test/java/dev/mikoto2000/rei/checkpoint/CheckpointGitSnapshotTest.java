package dev.mikoto2000.rei.checkpoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("integration")
class CheckpointGitSnapshotTest {
  @TempDir Path root;

  private String git(String... arguments) throws Exception {
    var command = new ArrayList<>(List.of("git", "-C", root.toString()));
    command.addAll(List.of(arguments));
    var process = new ProcessBuilder(command).redirectErrorStream(true).start();
    var output =
        new String(
            process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    assertEquals(0, process.waitFor(), output);
    return output.strip();
  }

  private PersistentCheckpoint checkpoint(Map<String, String> snapshot) {
    var json = new ObjectMapper().registerModule(new JavaTimeModule());
    var tree =
        json.valueToTree(
            PersistentCheckpoint.initial("task", "A", "session", "run", root, "request"));
    ((com.fasterxml.jackson.databind.node.ObjectNode) tree).set("git", json.valueToTree(snapshot));
    return json.convertValue(tree, PersistentCheckpoint.class);
  }

  @Test
  void unbornDirtyCommittedDetachedAndChangedHeadAreDetected() throws Exception {
    git("init", "--quiet", "-b", "fixture");
    var unborn = CheckpointReconciler.git(root);
    assertEquals("fixture", unborn.get("branch"));
    assertEquals("(initial)", unborn.get("head"));
    assertEquals("", unborn.get("workTree"));
    Files.writeString(root.resolve("日本語 file.txt"), "original");
    var dirty = CheckpointReconciler.git(root);
    assertFalse(dirty.get("workTree").isEmpty());
    assertFalse(
        new CheckpointReconciler(null).check(checkpoint(unborn), false).changed().isEmpty());
    git("add", ".");
    git(
        "-c",
        "user.name=Fixture",
        "-c",
        "user.email=fixture@example.invalid",
        "commit",
        "--quiet",
        "-m",
        "fixture");
    var clean = CheckpointReconciler.git(root);
    assertEquals(git("rev-parse", "HEAD"), clean.get("head"));
    assertTrue(new CheckpointReconciler(null).check(checkpoint(clean), false).changed().isEmpty());
    git("checkout", "--quiet", "--detach");
    assertEquals("(detached)", CheckpointReconciler.git(root).get("branch"));
    assertFalse(new CheckpointReconciler(null).check(checkpoint(clean), false).changed().isEmpty());
  }

  @Test
  void legacyDirtySnapshotDoesNotReportAFormatOnlyChange() throws Exception {
    git("init", "--quiet", "-b", "fixture");
    Files.writeString(root.resolve("file.txt"), "original");
    git("add", ".");
    git(
        "-c",
        "user.name=Fixture",
        "-c",
        "user.email=fixture@example.invalid",
        "commit",
        "--quiet",
        "-m",
        "fixture");
    Files.writeString(root.resolve("file.txt"), "modified");
    var legacy =
        Map.of(
            "branch",
            git("rev-parse", "--abbrev-ref", "HEAD"),
            "head",
            git("rev-parse", "HEAD"),
            "workTree",
            git("status", "--porcelain=v1", "--untracked-files=normal"));
    assertTrue(new CheckpointReconciler(null).check(checkpoint(legacy), false).changed().isEmpty());
    Files.writeString(root.resolve("extra.txt"), "changed");
    assertFalse(
        new CheckpointReconciler(null).check(checkpoint(legacy), false).changed().isEmpty());
  }
}
