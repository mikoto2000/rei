package dev.mikoto2000.rei.workcontext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

/** 作業文脈保存と検索の窓口の結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class WorkContextRepositoryTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path directory;

  /** {@code open} の条件を再現するための補助情報を返す。 */
  WorkContextRepository open() {
    var ds = new SQLiteDataSource();
    ds.setUrl("jdbc:sqlite:" + directory.resolve("work.db"));
    return new WorkContextRepository(ds);
  }

  /** 検証で使用する状態の記録を、指定された条件に合わせて用意する。 */
  WorkContext snapshot(String project, long revision) {
    return new WorkContext(
        project, revision, Instant.now(), Instant.now(), null, List.of(), Set.of("run-1"));
  }

  /** 再開の後も対象の情報が残ることを確認する。 */
  @Test
  void survivesRestartAndSeparatesProjectsWithRevisionHistory() {
    var repo = open();
    repo.save(snapshot("A", 1), 0);
    repo.save(snapshot("A", 2), 1);
    assertEquals(2, open().current("A").orElseThrow().revision());
    assertTrue(open().current("B").isEmpty());
    assertEquals(
        List.of(2L, 1L), open().history("A", 20).stream().map(WorkContext::revision).toList());
  }

  /** 作業文脈の引き継ぎで、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void staleWriterCannotLosePreviousState() {
    var repo = open();
    repo.save(snapshot("A", 1), 0);
    assertThrows(
        java.util.ConcurrentModificationException.class, () -> open().save(snapshot("A", 1), 0));
    assertEquals(1, repo.current("A").orElseThrow().revision());
    assertEquals(1, repo.history("A", 20).size());
  }

  /** 作業文脈の引き継ぎの中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void interruptedUpdateAndStorageFailureKeepHeadAndHistoryAtomic() throws Exception {
    var repo = open();
    repo.save(snapshot("A", 1), 0);
    Thread.currentThread().interrupt();
    try {
      assertThrows(
          java.util.concurrent.CancellationException.class, () -> repo.save(snapshot("A", 2), 1));
    } finally {
      Thread.interrupted();
    }
    try (var connection =
            java.sql.DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("work.db"));
        var statement = connection.createStatement()) {
      statement.execute(
          "CREATE TRIGGER fail_work_context BEFORE INSERT ON work_context_revisions WHEN NEW.revision=2 BEGIN SELECT RAISE(ABORT,'simulated storage failure'); END");
    }
    assertThrows(RuntimeException.class, () -> repo.save(snapshot("A", 2), 1));
    assertEquals(1, open().current("A").orElseThrow().revision());
    assertEquals(1, open().history("A", 20).size());
  }
}
