package dev.mikoto2000.rei.dot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 状態表示状態の保存同時実行数の結果と状態の契約を検証するテスト。 */
class DotPersistenceConcurrencyTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** 日時や期限の条件を再現するために使う検証時刻。 */
  DotRequestRepositoryTest.Time clock = new DotRequestRepositoryTest.Time();

  /** 検証に使うリポジトリ。 */
  DotRequestRepository repo;

  /** 各検証を独立した状態で始められるよう、依存先と検証データを準備する。 */
  @BeforeEach
  void start() throws Exception {
    try (var gate = new dev.mikoto2000.rei.storage.StorageMigrationCoordinator(root)) {
      gate.prepare();
    }
    repo = new DotRequestRepository(root, clock);
  }

  /** 指定された条件を再現するための検証対象や入力情報を作成する。 */
  String create() {
    return (String)
        repo.create(
                "p",
                null,
                null,
                root.toString(),
                Map.of("category", "REVIEW", "instruction", "Review"),
                Duration.ofHours(1))
            .get("request_id");
  }

  /** 利用者の状態表示で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void simultaneousClaimHasOneWinner() throws Exception {
    String id = create();
    try (var pool = Executors.newFixedThreadPool(2)) {
      var barrier = new CyclicBarrier(2);
      var calls = new ArrayList<Future<Boolean>>();
      for (int i = 0; i < 2; i++) {
        String claim = "claim" + i;
        calls.add(
            pool.submit(
                () -> {
                  barrier.await();
                  try {
                    repo.claim("p", id, claim);
                    return true;
                  } catch (DotException conflict) {
                    return false;
                  }
                }));
      }
      assertEquals(
          1,
          calls.stream()
              .filter(
                  f -> {
                    try {
                      return f.get();
                    } catch (Exception e) {
                      throw new RuntimeException(e);
                    }
                  })
              .count());
    }
  }

  /** 利用者の状態表示の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void cancelAndResultCannotAdoptCancelledWork() throws Exception {
    String id = create();
    repo.claim("p", id, "claim");
    try (var pool = Executors.newFixedThreadPool(2)) {
      var barrier = new CyclicBarrier(2);
      var cancel =
          pool.submit(
              () -> {
                barrier.await();
                return repo.cancel("p", id);
              });
      var submit =
          pool.submit(
              () -> {
                barrier.await();
                try {
                  return repo.submit(
                      "p", id, "claim", "submission", Map.of("summary", "report"), "hash");
                } catch (DotException late) {
                  return Map.of();
                }
              });
      cancel.get();
      submit.get();
      assertEquals("CANCELLED", repo.get("p", id).get("status"));
    }
  }

  /** 利用者の状態表示で、条件が変わっても、引き継ぐべき情報や状態を保持することを確認する。 */
  @Test
  void resultsExpireButSubmissionTombstoneSurvives() throws Exception {
    String id = create();
    repo.claim("p", id, "claim");
    var receipt = repo.submit("p", id, "claim", "submission", Map.of("summary", "report"), "hash");
    clock.now = clock.now.plus(Duration.ofDays(31));
    repo.maintain();
    try (var c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + root.resolve("storage.db"));
        var s = c.createStatement();
        var r = s.executeQuery("SELECT result FROM dot_receipts")) {
      assertTrue(r.next());
      assertNull(r.getString(1));
    }
    assertEquals(
        receipt,
        new DotRequestRepository(root, clock)
            .submit("p", id, "claim", "submission", Map.of("summary", "report"), "hash"));
  }
}
