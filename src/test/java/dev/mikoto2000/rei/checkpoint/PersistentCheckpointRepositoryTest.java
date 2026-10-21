package dev.mikoto2000.rei.checkpoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

/** 保存される再開用の記録保存と検索の窓口の結果と状態の契約を検証するテスト。 */
@Tag("integration")
class PersistentCheckpointRepositoryTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path directory;

  /** {@code open} の条件を再現するための補助情報を返す。 */
  PersistentCheckpointRepository open() {
    var source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + directory.resolve("checkpoint.db"));
    return new PersistentCheckpointRepository(source, new CheckpointProperties());
  }

  /** 作業の中断と再開で、保存した情報を、再起動や復元後の処理へ正しく引き継ぐことを確認する。 */
  @Test
  void restartDeduplicationAndOptimisticUpdates() {
    var repo = open();
    var first = PersistentCheckpoint.initial("task", "A", "session", "run", directory, "request");
    repo.save(first, 0, "event");
    assertEquals(1, open().get("A", "task").revision());
    assertTrue(open().list("B").isEmpty());
    repo.save(first, 0, "event");
    assertThrows(ConcurrentModificationException.class, () -> repo.save(first, 0, "other"));
    assertEquals(1, repo.get("A", "task").revision());
  }

  /** 作業の中断と再開で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void onlyOneOwnerCanAcquireAndReleaseDoesNotEraseHistory() {
    var repo = open();
    repo.save(
        PersistentCheckpoint.initial("task", "A", "session", "run", directory, "request"),
        0,
        "event");
    assertTrue(repo.acquire("A", "task", "new-run"));
    assertFalse(open().acquire("A", "task", "other-run"));
    repo.release("A", "task", "other-run");
    assertFalse(repo.acquire("A", "task", "other-run"));
    repo.release("A", "task", "new-run");
    assertTrue(repo.acquire("A", "task", "other-run"));
    assertEquals(1, repo.get("A", "task").revision());
  }

  /** 失敗について、保持する前回改訂番号を書き込むことを確認する。 */
  @Test
  void failedWritePreservesPreviousRevision() throws Exception {
    var repo = open();
    var first = PersistentCheckpoint.initial("task", "A", "session", "run", directory, "request");
    repo.save(first, 0, "event");
    try (var c =
            java.sql.DriverManager.getConnection(
                "jdbc:sqlite:" + directory.resolve("checkpoint.db"));
        var s = c.createStatement()) {
      s.execute(
          "CREATE TRIGGER inject_failure BEFORE INSERT ON checkpoint_revisions WHEN NEW.revision=2 BEGIN SELECT RAISE(ABORT,'injected'); END");
    }
    assertThrows(RuntimeException.class, () -> repo.save(first, 1, "next"));
    assertEquals(1, open().get("A", "task").revision());
  }

  /** 作業の中断と再開で、保存した情報を、再起動や復元後の処理へ正しく引き継ぐことを確認する。 */
  @Test
  void deadOwnerAndReusedPidCannotBlockRecovery() throws Exception {
    var repo = open();
    repo.save(
        PersistentCheckpoint.initial("task", "A", "session", "run", directory, "request"),
        0,
        "start");
    assertTrue(repo.acquire("A", "task", "old"));
    try (var c =
            java.sql.DriverManager.getConnection(
                "jdbc:sqlite:" + directory.resolve("checkpoint.db"));
        var s = c.prepareStatement("UPDATE checkpoint_heads SET owner=?")) {
      s.setString(1, ProcessHandle.current().pid() + "@1970-01-01T00:00:00Z");
      s.executeUpdate();
    }
    assertFalse(repo.leased("A", "task"));
    assertTrue(open().acquire("A", "task", "recovered"));
  }

  /** 単一改訂番号上限について、一括反映置換を許可することを確認する。 */
  @Test
  void singleRevisionLimitAllowsAtomicReplacementAndCorruptionKeepsLatestValidRevision()
      throws Exception {
    var settings = new CheckpointProperties();
    settings.setMaxRevisions(1);
    var ds = new SQLiteDataSource();
    ds.setUrl("jdbc:sqlite:" + directory.resolve("work.db"));
    var repo = new PersistentCheckpointRepository(ds, settings);
    var first = PersistentCheckpoint.initial("task", "A", "session", "run", directory, "request");
    repo.save(first, 0, "start");
    repo.save(first, 1, "next");
    assertEquals(2, repo.get("A", "task").revision());
    settings.setMaxRevisions(10);
    repo.save(first, 2, "third");
    try (var c =
            java.sql.DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("work.db"));
        var s = c.createStatement()) {
      s.executeUpdate("UPDATE checkpoint_revisions SET snapshot='broken' WHERE revision=3");
    }
    assertEquals(2, repo.get("A", "task").revision());
    assertEquals(List.of("Corrupt checkpoint"), repo.diagnostics("A", "task"));
  }

  /** 作業の中断と再開で、競合する要求でも、実行順序と作業の分離を守ることを確認する。 */
  @Test
  void concurrentWritersHaveExactlyOneWinner() throws Exception {
    var repo = open();
    var first = PersistentCheckpoint.initial("task", "A", "session", "run", directory, "request");
    repo.save(first, 0, "start");
    var barrier = new java.util.concurrent.CyclicBarrier(2);
    var successes = new java.util.concurrent.atomic.AtomicInteger();
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var work = new ArrayList<java.util.concurrent.Future<?>>();
      for (int i = 0; i < 2; i++) {
        String event = "event-" + i;
        var writer = open();
        work.add(
            pool.submit(
                () -> {
                  try {
                    barrier.await();
                    writer.save(first, 1, event);
                    successes.incrementAndGet();
                  } catch (ConcurrentModificationException expected) {
                  } catch (Exception error) {
                    throw new RuntimeException(error);
                  }
                }));
      }
      for (var result : work) result.get();
    }
    assertEquals(1, successes.get());
    assertEquals(2, open().get("A", "task").revision());
  }

  /** 作業の中断と再開で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void pruningDoesNotAllowAnOldEventToBeSavedAgainOrDiscardLatestValidState() throws Exception {
    var repo = open();
    var first = PersistentCheckpoint.initial("task", "A", "session", "run", directory, "request");
    repo.save(first, 0, "first");
    repo.save(first, 1, "second");
    try (var c =
            java.sql.DriverManager.getConnection(
                "jdbc:sqlite:" + directory.resolve("checkpoint.db"));
        var s = c.createStatement()) {
      s.executeUpdate(
          "UPDATE checkpoint_revisions SET created='1970-01-01T00:00:00Z' WHERE revision=1");
    }
    repo.save(first, 2, "third");
    repo.save(first, 0, "first");
    assertEquals(3, repo.get("A", "task").revision());
    try (var c =
            java.sql.DriverManager.getConnection(
                "jdbc:sqlite:" + directory.resolve("checkpoint.db"));
        var s = c.createStatement()) {
      s.executeUpdate("UPDATE checkpoint_revisions SET snapshot='corrupt' WHERE revision=3");
      s.executeUpdate(
          "UPDATE checkpoint_revisions SET created='1970-01-01T00:00:00Z' WHERE revision=2");
    }
    var other =
        PersistentCheckpoint.initial(
            "other", "A", "session", "other-run", directory, "other request");
    repo.save(other, 0, "other-start");
    assertEquals(2, repo.get("A", "task").revision());
    assertFalse(repo.diagnostics("A", "task").isEmpty());
  }
}
