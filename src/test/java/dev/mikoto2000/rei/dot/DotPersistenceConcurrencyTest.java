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

class DotPersistenceConcurrencyTest {
  @TempDir Path root;
  DotRequestRepositoryTest.Time clock = new DotRequestRepositoryTest.Time();
  DotRequestRepository repo;

  @BeforeEach
  void start() throws Exception {
    try (var gate = new dev.mikoto2000.rei.storage.StorageMigrationCoordinator(root)) {
      gate.prepare();
    }
    repo = new DotRequestRepository(root, clock);
  }

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
