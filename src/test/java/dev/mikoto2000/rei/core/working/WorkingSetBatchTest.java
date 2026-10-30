package dev.mikoto2000.rei.core.working;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("integration")
class WorkingSetBatchTest {
  @TempDir Path root;

  @Test
  void fiftyReadsRemainVisibleAndOnlyOuterClosePublishesSnapshot() throws Exception {
    var file = root.resolve("working.json");
    var set = new WorkingSet(50, Clock.systemUTC());
    set.enablePersistence(file);
    try (var outer = set.batchPersistence()) {
      for (int i = 0; i < 50; i++) {
        try (var inner = set.batchPersistence()) {
          set.recordRead(root.resolve("file-" + i));
        }
        assertEquals(i + 1, set.getFiles().size());
        assertFalse(Files.exists(file));
      }
    }
    var restarted = new WorkingSet();
    restarted.enablePersistence(file);
    assertEquals(50, restarted.getFiles().size());
    // A later non-batched update must write immediately (the thread-local was removed).
    set.recordRead(root.resolve("next"));
    restarted = new WorkingSet();
    restarted.enablePersistence(file);
    assertTrue(restarted.contains(root.resolve("next")));
  }

  @Test
  void failureFlushesSuccessfulReadsAndRetainsEviction() {
    var file = root.resolve("working.json");
    var set = new WorkingSet(1, Clock.systemUTC());
    set.enablePersistence(file);
    assertThrows(
        IllegalStateException.class,
        () -> {
          try (var batch = set.batchPersistence()) {
            set.recordRead(root.resolve("one"));
            set.recordRead(root.resolve("two"));
            assertFalse(set.contains(root.resolve("one")));
            throw new IllegalStateException("fixture failure");
          }
        });
    var restarted = new WorkingSet();
    restarted.enablePersistence(file);
    assertFalse(restarted.contains(root.resolve("one")));
    assertTrue(restarted.contains(root.resolve("two")));
  }

  @Test
  void anotherThreadCanPersistWhileABatchIsOpen() throws Exception {
    var file = root.resolve("working.json");
    var set = new WorkingSet();
    set.enablePersistence(file);
    try (var batch = set.batchPersistence();
        var pool = java.util.concurrent.Executors.newSingleThreadExecutor()) {
      set.recordRead(root.resolve("one"));
      pool.submit(() -> set.recordRead(root.resolve("two")))
          .get(5, java.util.concurrent.TimeUnit.SECONDS);
      assertTrue(Files.exists(file));
      set.recordRead(root.resolve("three"));
    }
    var restarted = new WorkingSet();
    restarted.enablePersistence(file);
    assertEquals(3, restarted.getFiles().size());
  }
}
