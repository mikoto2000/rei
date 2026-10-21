package dev.mikoto2000.rei.externalagent;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

/** 実装要求保存と検索の窓口の結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class ImplementationRequestRepositoryTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** 外部エージェントへの委譲で、再送や重複を、受付と実行の規則に従って扱うことを確認する。 */
  @Test
  void terminalReceiptReconciliationIsIdempotentAndCannotDowngradeResult() {
    var source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + root.resolve("requests.db"));
    var first = new ImplementationRequestRepository(source, Clock.systemUTC());
    var second = new ImplementationRequestRepository(source, Clock.systemUTC());
    first.save(
        new ImplementationRequestRepository.Request(
            "id",
            1,
            "hash",
            "project",
            root.toString(),
            "session",
            "run",
            "run:human",
            "NATURAL_LANGUAGE",
            "codex",
            "base",
            null,
            "canonical",
            "{}",
            "AUTO_APPROVE",
            null,
            "AUTHORIZED",
            null,
            null,
            Instant.now(),
            Instant.now()));
    assertTrue(first.claim("id", "one"));
    second.finish("id", "RESULT_AVAILABLE", "id", "same-result");
    assertDoesNotThrow(() -> first.finish("id", "RESULT_AVAILABLE", "id", "same-result"));
    assertThrows(IllegalStateException.class, () -> first.finish("id", "UNKNOWN", "id", null));
    assertEquals("RESULT_AVAILABLE", second.get("id").executionStatus());
  }

  /** 一括反映利用権の確保について、別の保存と検索の窓口の後も対象の情報が残ることを確認する。 */
  @Test
  void atomicClaimSurvivesAnotherRepositoryAndOnlyOneConcurrentCallerWins() throws Exception {
    var source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + root.resolve("requests.db"));
    var first = new ImplementationRequestRepository(source, Clock.systemUTC());
    var second = new ImplementationRequestRepository(source, Clock.systemUTC());
    var record =
        new ImplementationRequestRepository.Request(
            "id",
            1,
            "hash",
            "project",
            root.toString(),
            "session",
            "run",
            "run:human",
            "NATURAL_LANGUAGE",
            "codex",
            "base",
            null,
            "canonical",
            "{}",
            "AUTO_APPROVE",
            null,
            "AUTHORIZED",
            null,
            null,
            Instant.now(),
            Instant.now());
    first.save(record);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var start = new CountDownLatch(1);
      var a =
          pool.submit(
              () -> {
                start.await();
                return first.claim("id", "one");
              });
      var b =
          pool.submit(
              () -> {
                start.await();
                return second.claim("id", "two");
              });
      start.countDown();
      assertNotEquals(a.get(), b.get());
    }
    assertEquals("EXECUTING", second.get("id").executionStatus());
    assertFalse(second.claim("id", "restart"));
    second.finish("id", "UNKNOWN", null, null);
    assertFalse(first.claim("id", "retry"));
  }
}
