package dev.mikoto2000.rei.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.offset;

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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** レビューツール一覧の結果と状態の契約を検証するテスト。 */
class ReviewToolsTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path dir;

  /** 変更内容のレビューで、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void changedSourceRebuildsCoverageWithoutReusingOldReview() throws Exception {
    Files.writeString(dir.resolve("lesson.md"), "old text");
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
      var job = tools.beginReview(List.of("lesson.md"), "correctness", "v1");
      var page =
          tools.readReviewFile(job.id(), "lesson.md", job.targets().getFirst().version(), 0, 512);
      tools.confirmed(owner, List.of(page));
      tools.submitReviewResult(job.id(), page.range(), "checked", List.of());
      Files.writeString(dir.resolve("lesson.md"), "new text");
      assertThat(tools.reviewStatus(job.id()).status()).isEqualTo("STALE_VERSION");
      var rebuilt = tools.restartReview(job.id());
      assertThat(rebuilt.id()).isEqualTo(job.id());
      assertThat(rebuilt.status()).isEqualTo("REVIEW_PARTIAL");
      assertThat(rebuilt.targets().getFirst().version()).isNotEqualTo(page.range().version());
      assertThat(rebuilt.ranges().getFirst().stale()).isTrue();
    }
  }

  /** 変更内容のレビューで、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void allExactSourcePagesMustBeConfirmedAndReviewedBeforeCompletion() throws Exception {
    String source = "\uFEFF教材🙂\r\n" + "中間の矛盾\\\"\n".repeat(100);
    Files.writeString(dir.resolve("lesson.md"), source);
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
      var job = tools.beginReview(List.of("lesson.md"), "correctness", "v1");
      String version = job.targets().getFirst().version();
      int offset = 0;
      var combined = new StringBuilder();
      while (offset < source.length()) {
        var page = tools.readReviewFile(job.id(), "lesson.md", version, offset, 256);
        combined.append(page.content());
        assertThat(
                TokenEstimator.conservative().text(StorageDatabase.JSON.writeValueAsString(page)))
            .isLessThanOrEqualTo(256);
        assertThatThrownBy(
                () -> tools.submitReviewResult(job.id(), page.range(), "checked", List.of()))
            .hasMessageContaining("REVIEW_RESULT_INVALID");
        tools.confirmed(owner, List.of(page));
        tools.submitReviewResult(job.id(), page.range(), "checked", List.of());
        offset = page.nextOffset();
      }
      assertThat(combined.toString()).isEqualTo(source);
      assertThat(tools.reviewStatus(job.id()).status()).isEqualTo("COMPLETE");
      Files.writeString(dir.resolve("lesson.md"), source + "changed");
      assertThat(tools.reviewStatus(job.id()).status()).isEqualTo("STALE_VERSION");
    }
  }
}
