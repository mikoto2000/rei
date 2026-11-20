package dev.mikoto2000.rei.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepositoryFileInventoryTest {
  @TempDir Path root;

  private void write(String name, String content) throws IOException {
    Path file = root.resolve(name);
    Files.createDirectories(file.getParent());
    Files.writeString(file, content);
  }

  @Test
  void mapsNonGitProjectAndRefreshesChangedAndDeletedSources() throws Exception {
    write(
        "module/src/main/java/App.java", "class App { public static void main(String[] args) {} }");
    write("README.md", "project");
    var service = new RepositoryMapService();
    var first = service.map(root, "App", 10);
    assertEquals(2, first.filesScanned());
    assertEquals("App.main", first.summary().entryPoints().getFirst().symbol());
    assertTrue(first.partial());
    assertTrue(first.warnings().stream().anyMatch(w -> w.contains("Non-Git directory")));
    write("module/src/main/java/App.java", "class Changed {}");
    var changed = service.map(root, "Changed", 10);
    assertFalse(first.version().equals(changed.version()));
    assertEquals(1, changed.items().size());
    Files.delete(root.resolve("module/src/main/java/App.java"));
    assertTrue(service.map(root, "App", 10).items().isEmpty());
  }

  @Test
  void prunesCachesBuildOutputsMetadataAndSecretsBeforeCollectingFiles() throws Exception {
    write("src/App.java", "class App {}");
    for (String directory :
        List.of(
            ".tools/cache",
            ".cache",
            ".worktrees",
            ".webaudit-worktrees",
            "node_modules",
            "target",
            "build",
            ".venv",
            "venv",
            ".gradle",
            "dist",
            "out",
            "__pycache__",
            ".codex",
            ".agents",
            ".aws",
            ".hg",
            ".svn",
            "nested/.git")) {
      write(directory + "/Hidden.java", "class Hidden {}");
    }
    write(".env", "PRIVATE");
    write("nested/credentials.json", "PRIVATE");
    write("nested/server.key", "PRIVATE");
    assertEquals(List.of("src/App.java"), RepositoryFileInventory.scan(root).paths());
    assertEquals(1, new RepositoryMapService().map(root, "", 10).filesScanned());
  }

  @Test
  void boundsFileCountAndDirectoryDepthAndReportsPartialResults() throws Exception {
    for (int index = 0; index < 1025; index++) write("file" + index + ".txt", "");
    var inventory = RepositoryFileInventory.scan(root);
    assertEquals(1024, inventory.paths().size());
    assertTrue(inventory.warnings().contains("File inventory limited to 1024 entries"));
    Path deepRoot = Files.createDirectory(root.resolve("deep"));
    Path deepest = deepRoot;
    for (int index = 0; index < 33; index++) deepest = Files.createDirectory(deepest.resolve("d"));
    Files.writeString(deepest.resolve("Hidden.java"), "class Hidden {}");
    var deep = RepositoryFileInventory.scan(deepRoot);
    assertTrue(deep.paths().isEmpty());
    assertTrue(deep.warnings().contains("Filesystem inventory depth limited to 32 levels"));
  }

  @Test
  void doesNotFollowDirectoryOrFileSymlinks(@TempDir Path outside) throws Exception {
    Files.writeString(outside.resolve("Outside.java"), "class Outside {}");
    try {
      Files.createSymbolicLink(root.resolve("linked"), outside);
      Files.createSymbolicLink(root.resolve("Linked.java"), outside.resolve("Outside.java"));
    } catch (IOException | UnsupportedOperationException error) {
      Assumptions.abort("Symlinks unavailable: " + error.getClass().getSimpleName());
    }
    assertTrue(RepositoryFileInventory.scan(root).paths().isEmpty());
  }

  @Test
  void honoursCancellation() {
    Thread.currentThread().interrupt();
    try {
      assertThrows(CancellationException.class, () -> RepositoryFileInventory.scan(root));
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void preservesGitIgnoreRulesIncludingWhenProjectIsASubdirectory() throws Exception {
    var init = new ProcessBuilder("git", "init", "--quiet").directory(root.toFile()).start();
    assertTrue(init.waitFor(5, TimeUnit.SECONDS));
    assertEquals(
        0,
        init.exitValue(),
        new String(init.getErrorStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
    write(".gitignore", "ignored/\n");
    write("src/App.java", "class App {}");
    write("src/ignored/Hidden.java", "class Hidden {}");
    var map = new RepositoryMapService().map(root.resolve("src"), "", 10);
    assertEquals(1, map.filesScanned());
    assertEquals("App.java", map.items().getFirst().path());
    assertFalse(map.partial());
  }

  @Test
  void brokenGitMetadataDoesNotFallBackToUnignoredFilesystemScan() throws Exception {
    write(".git", "gitdir: missing\n");
    write("App.java", "class App {}");
    assertTrue(RepositoryFileInventory.hasGitMetadata(root));
    assertThrows(IOException.class, () -> new RepositoryMapService().map(root, "", 10));
  }
}
