package dev.mikoto2000.rei.dot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 状態表示Creation要求やイベントの再送の結果と状態の契約を検証するテスト。 */
class DotCreationReplayTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** 失われた応答の後も対象の情報が残ることを確認する。 */
  @Test
  void sameCreationIdSurvivesLostResponseAndRejectsChangedOwnerOrContent() throws Exception {
    try (var gate = new dev.mikoto2000.rei.storage.StorageMigrationCoordinator(root)) {
      gate.prepare();
    }
    var clock = new DotRequestRepositoryTest.Time();
    var repo = new DotRequestRepository(root, clock);
    String key = UUID.randomUUID().toString();
    var input = DotPayload.publication(Map.of("instruction", "research"));
    var first = repo.create("p", null, null, root.toString(), input, Duration.ofHours(1), key);
    clock.now = clock.now.plusSeconds(1);
    assertEquals(
        first,
        new DotRequestRepository(root, clock)
            .create("p", null, null, root.toString(), input, Duration.ofHours(1), key));
    assertEquals(1, repo.list(Set.of("p"), "PENDING", null, 20).size());
    assertThrows(
        DotException.class,
        () -> repo.create("other", null, null, root.toString(), input, Duration.ofHours(1), key));
    assertThrows(
        DotException.class,
        () ->
            repo.create(
                "p",
                null,
                null,
                root.toString(),
                DotPayload.publication(Map.of("instruction", "different")),
                Duration.ofHours(1),
                key));
  }
}
