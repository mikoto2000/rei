package dev.mikoto2000.rei.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileInventoryLimitTest {
  @TempDir Path root;

  private List<String> paths(int count) {
    return IntStream.range(0, count).mapToObj(i -> "file-" + i + ".txt").toList();
  }

  @Test
  void accepts4096NamesWithoutReadingContentsAndCachesTheInventory() throws Exception {
    var reads = new AtomicInteger();
    var snapshots =
        new FileSnapshots(
            path -> {
              reads.incrementAndGet();
              throw new AssertionError("Inventory must not read file contents");
            });
    var expected = paths(4096).stream().sorted().toList();
    var actual = snapshots.inventory(".", () -> expected);
    assertEquals(expected, actual);
    assertEquals(
        actual,
        snapshots.inventory(
            ".",
            () -> {
              throw new AssertionError("The same request must reuse its inventory");
            }));
    assertEquals(0, reads.get());
  }

  @Test
  void rejects4097NamesAndAllowsANarrowerRetry() throws Exception {
    var snapshots = new FileSnapshots();
    var error = assertThrows(IOException.class, () -> snapshots.inventory(".", () -> paths(4097)));
    assertTrue(error.getMessage().contains("File inventory limit reached (4096)"));
    assertTrue(error.getMessage().contains("narrow baseDir/globs"));
    assertEquals(4096, snapshots.inventory(".", () -> paths(4096)).size());
  }

  @Test
  void duplicateNamesDoNotConsumeTheInventoryBudgetAndPathLengthRemainsBounded() throws Exception {
    var duplicates =
        java.util.stream.Stream.concat(paths(4096).stream(), paths(4096).stream()).toList();
    assertEquals(4096, new FileSnapshots().inventory(".", () -> duplicates).size());
    assertThrows(
        IOException.class,
        () -> new FileSnapshots().inventory(".", () -> List.of("x".repeat(1025))));
  }

  @Test
  @org.junit.jupiter.api.Tag("integration")
  void nonGitFallbackPreservesTheOverflowSentinel() throws Exception {
    for (var path : paths(4098)) Files.createFile(root.resolve(path));
    var tools =
        new Tools() {
          @Override
          List<String> gitLsFiles(List<String> specs, Path directory) {
            return null;
          }
        };
    var listed = tools.listFile(".", root);
    assertEquals(4097, listed.size());
    assertThrows(IOException.class, () -> new FileSnapshots().inventory(".", () -> listed));
  }

  @Test
  @org.junit.jupiter.api.Tag("integration")
  void largerInventoryDoesNotIncreaseThe1024ContentSnapshotLimit() throws Exception {
    var names = paths(1025);
    for (var path : names) Files.createFile(root.resolve(path));
    var reads = new AtomicInteger();
    var snapshots =
        new FileSnapshots(
            path -> {
              reads.incrementAndGet();
              return Files.readAllBytes(path);
            });
    assertEquals(1025, snapshots.inventory(".", () -> names).size());
    for (int i = 0; i < 1024; i++) snapshots.get(root, root.resolve(names.get(i)));
    var error =
        assertThrows(IOException.class, () -> snapshots.get(root, root.resolve(names.get(1024))));
    assertEquals("Snapshot file limit reached", error.getMessage());
    assertEquals(1024, reads.get());
  }
}
