package dev.mikoto2000.rei.review;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mikoto2000.rei.core.Tools;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.AgentRunScope;
import dev.mikoto2000.rei.core.contextbudget.RawToolResultStore;
import dev.mikoto2000.rei.core.contextbudget.TokenEstimator;
import dev.mikoto2000.rei.storage.StorageDatabase;
import dev.mikoto2000.rei.storage.StorageMigrationCoordinator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** レビュー継続入力の結果と状態の契約を検証するテスト。 */
class ReviewContinuationTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path dir;

  /** 変更内容のレビューで、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void pendingRangesAndDeletedSourceRemainVisible() throws Exception {
    Files.writeString(dir.resolve("source.md"), "first\nlast");
    try (var migration = new StorageMigrationCoordinator(dir.resolve("data"))) {
      migration.prepare();
    }
    var ledger = new ReviewCoverageLedger(new StorageDatabase(dir.resolve("data")));
    var tools =
        new ReviewTools(
            ledger,
            new RawToolResultStore(dir.resolve("data")),
            new Tools(),
            TokenEstimator.conservative());
    var owner = new AgentRunContext("run", "chat", dir, "project");
    try (var scope = AgentRunScope.open(owner)) {
      var job = tools.beginReview(List.of("source.md"), "review", "v1");
      assertThat(tools.reviewPending(job.id()).getFirst().start()).isZero();
      var page =
          tools.readReviewFile(job.id(), "source.md", job.targets().getFirst().version(), 0, 512);
      String inner = StorageDatabase.JSON.writeValueAsString(page);
      String envelope =
          StorageDatabase.JSON.writeValueAsString(
              Map.of("output", inner, "outputSha256", ReviewTools.hash(inner), "truncated", false));
      assertThat(ReviewTools.receipt(envelope).range()).isEqualTo(page.range());
      tools.confirmed(owner, List.of(page));
      tools.submitReviewResult(job.id(), page.range(), "checked", List.of());
      assertThat(tools.reviewPending(job.id())).isEmpty();
      Files.delete(dir.resolve("source.md"));
      assertThat(tools.reviewStatus(job.id()).status()).isEqualTo("STALE_VERSION");
      assertThat(tools.reviewPending(job.id())).isNotEmpty();
    }
  }
}
