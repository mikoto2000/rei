package dev.mikoto2000.rei.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.AgentRunScope;
import dev.mikoto2000.rei.storage.StorageDatabase;
import dev.mikoto2000.rei.storage.StorageMigrationCoordinator;
import dev.mikoto2000.rei.tooldelivery.ReadingMetrics;
import dev.mikoto2000.rei.tooldelivery.ReadingStateStore;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReadingMetricsPersistenceTest {
  @TempDir Path root;
  StorageDatabase db;
  AgentRunContext first, second;

  @BeforeEach
  void setup() throws Exception {
    new Tools();
    var data = root.resolve("data");
    try (var migration = new StorageMigrationCoordinator(data)) {
      migration.prepare();
    }
    db = new StorageDatabase(data);
    first = new AgentRunContext(UUID.randomUUID().toString(), "first", root);
    do {
      second = new AgentRunContext(UUID.randomUUID().toString(), "second", root);
    } while (Math.floorMod(first.hashCode(), 64) == Math.floorMod(second.hashCode(), 64));
  }

  private Object access(boolean write, ReadingStateStore.Work<?> work) {
    return write ? db.transaction(work::run) : db.read(work::run);
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out");
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(error);
    }
  }

  @Test
  void slowSaveDoesNotHoldGlobalLockAndSameRunSavesStayOrdered() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var writes = new AtomicInteger();
    var store =
        new ReadingStateStore(
            (write, work) -> {
              if (write && writes.incrementAndGet() == 1) {
                entered.countDown();
                await(release);
              }
              return access(write, work);
            });
    ReadingMetrics.add(first, "count", 1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var save = executor.submit(() -> ReadingMetrics.flush(first, store));
      await(entered);
      try {
        executor
            .submit(
                () -> {
                  ReadingMetrics.add(first, "count", 1);
                  ReadingMetrics.add(second, "count", 7);
                  assertThat(ReadingMetrics.snapshot(second)).containsEntry("count", 7L);
                  ReadingMetrics.flush(second, store);
                })
            .get(3, TimeUnit.SECONDS);
        var queued = executor.submit(() -> ReadingMetrics.flush(first, store));
        release.countDown();
        save.get(3, TimeUnit.SECONDS);
        queued.get(3, TimeUnit.SECONDS);
        assertThat(store.load(first, "metrics", "totals", ReadingMetrics.Saved.class).values())
            .containsEntry("count", 2L);
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  void slowRestoreDoesNotBlockOtherRunsOrOverwriteConcurrentInitialization() throws Exception {
    var normal = ReadingTestStores.store(db);
    normal.save(
        first,
        "metrics",
        "totals",
        new ReadingMetrics.Saved(Map.of("saved", 12L), Set.of()),
        java.time.Duration.ofDays(1));
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var store =
        new ReadingStateStore(
            (write, work) -> {
              if (!write) {
                entered.countDown();
                await(release);
              }
              return access(write, work);
            });
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var restore = executor.submit(() -> ReadingMetrics.restore(first, store));
      await(entered);
      try {
        executor
            .submit(
                () -> {
                  ReadingMetrics.add(first, "current", 3);
                  ReadingMetrics.add(second, "count", 7);
                  ReadingMetrics.flush(second, normal);
                })
            .get(3, TimeUnit.SECONDS);
        release.countDown();
        restore.get(3, TimeUnit.SECONDS);
        assertThat(ReadingMetrics.snapshot(first))
            .containsEntry("current", 3L)
            .doesNotContainKey("saved");
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  void nestedOperationsSaveOnceAndKeepEveryCounter() throws Exception {
    var writes = new AtomicInteger();
    var store =
        new ReadingStateStore(
            (write, work) -> {
              if (write) writes.incrementAndGet();
              return access(write, work);
            });
    try (var scope = AgentRunScope.open(first)) {
      try (var outer = ReadingMetrics.operation("outer")) {
        try (var inner = ReadingMetrics.operation("inner", store)) {
          ReadingMetrics.add("count", 4);
        }
        assertThat(writes.get()).isZero();
      }
      assertThat(writes.get()).isEqualTo(1);
      var values = store.load(first, "metrics", "totals", ReadingMetrics.Saved.class).values();
      assertThat(values)
          .containsEntry("toolCalls", 2L)
          .containsEntry("count", 4L)
          .containsKeys("innerNanos", "outerNanos");
      try (var next = ReadingMetrics.operation("next", store)) {}
      assertThat(writes.get()).isEqualTo(2);
    }
  }

  @Test
  void exceptionAndPersistenceFailureReleaseOperationScope() {
    var writes = new AtomicInteger();
    var failed =
        new ReadingStateStore(
            (write, work) -> {
              if (write) writes.incrementAndGet();
              throw new IllegalStateException("unavailable");
            });
    try (var scope = AgentRunScope.open(first)) {
      assertThatThrownBy(
              () -> {
                try (var outer = ReadingMetrics.operation("outer", failed);
                    var inner = ReadingMetrics.operation("inner", failed)) {
                  throw new IllegalArgumentException("operation failed");
                }
              })
          .isInstanceOf(IllegalArgumentException.class);
      assertThat(writes.get()).isEqualTo(1);
      assertThat(ReadingMetrics.snapshot(first)).containsEntry("metricsPersistenceFailures", 1L);
      try (var next = ReadingMetrics.operation("next", failed)) {}
      assertThat(writes.get()).isEqualTo(2);
      assertThat(ReadingMetrics.snapshot(first)).containsEntry("metricsPersistenceFailures", 2L);
    }
  }

  @Test
  void restoreReadsExistingCountersAndExplicitFlushRemainsImmediate() {
    var store = ReadingTestStores.store(db);
    store.save(
        first,
        "metrics",
        "totals",
        new ReadingMetrics.Saved(Map.of("count", 12L), Set.of("old-body")),
        java.time.Duration.ofDays(1));
    ReadingMetrics.restore(first, store);
    ReadingMetrics.add(first, "count", 1);
    ReadingMetrics.flush(first, store);
    var saved = store.load(first, "metrics", "totals", ReadingMetrics.Saved.class);
    assertThat(saved.values()).containsEntry("count", 13L);
    assertThat(saved.bodies()).containsExactly("old-body");
    try (var scope = AgentRunScope.open(first);
        var operation = ReadingMetrics.operation("outer", store)) {
      ReadingMetrics.add("count", 1);
      ReadingMetrics.flush(first, store);
      assertThat(store.load(first, "metrics", "totals", ReadingMetrics.Saved.class).values())
          .containsEntry("count", 14L);
    }
  }
}
