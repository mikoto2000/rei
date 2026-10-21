package dev.mikoto2000.rei.dot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 状態表示許容件数の結果と状態の契約を検証するテスト。 */
class DotCapacityTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** 利用者の状態表示で、条件が変わっても、引き継ぐべき情報や状態を保持することを確認する。 */
  @Test
  void pendingReviewAndUnknownKeepTheActiveSlot() throws Exception {
    try (var gate = new dev.mikoto2000.rei.storage.StorageMigrationCoordinator(root)) {
      gate.prepare();
    }
    var clock = new DotRequestRepositoryTest.Time();
    var repo = new DotRequestRepository(root, clock);
    var ids = new ArrayList<String>();
    for (int i = 0; i < 3; i++)
      ids.add(
          (String)
              repo.create(
                      "p",
                      null,
                      null,
                      root.toString(),
                      DotPayload.publication(Map.of("instruction", "research")),
                      Duration.ofHours(1))
                  .get("request_id"));
    repo.claim("p", ids.get(0), "a");
    repo.claim("p", ids.get(1), "b");
    repo.submit("p", ids.get(0), "a", "one", Map.of("summary", "report"), "hash");
    assertThrows(DotException.class, () -> repo.claim("p", ids.get(2), "c"));
    clock.now = clock.now.plus(Duration.ofMinutes(31));
    repo.maintain();
    assertThrows(DotException.class, () -> repo.claim("p", ids.get(2), "c"));
    repo.review("p", ids.get(0), "VERIFIED", List.of(), "checked independently");
    repo.cancel("p", ids.get(1));
    assertEquals("CLAIMED", repo.claim("p", ids.get(2), "c").get("status"));
  }
}
