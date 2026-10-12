package dev.mikoto2000.rei.review;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import dev.mikoto2000.rei.storage.*;
import dev.mikoto2000.rei.core.*;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.core.contextbudget.*;

class ReviewToolsTest {
  @TempDir Path dir;
  @Test void allExactSourcePagesMustBeConfirmedAndReviewedBeforeCompletion() throws Exception {
    String source = "\uFEFF教材🙂\r\n" + "中間の矛盾\\\"\n".repeat(100);
    Files.writeString(dir.resolve("lesson.md"), source);
    try (var migration = new StorageMigrationCoordinator(dir.resolve("data"))) { migration.prepare(); }
    var ledger = new ReviewCoverageLedger(new StorageDatabase(dir.resolve("data")));
    var tools = new ReviewTools(ledger, new RawToolResultStore(dir.resolve("data")), new Tools(), TokenEstimator.conservative());
    var owner = new AgentRunContext("run", "chat", dir, "project");
    try (var scope = AgentRunScope.open(owner)) {
      var job = tools.beginReview(List.of("lesson.md"), "correctness", "v1");
      String version = job.targets().getFirst().version();
      int offset = 0; var combined = new StringBuilder();
      while (offset < source.length()) {
        var page = tools.readReviewFile(job.id(), "lesson.md", version, offset, 256);
        combined.append(page.content());
        assertThat(TokenEstimator.conservative().text(StorageDatabase.JSON.writeValueAsString(page))).isLessThanOrEqualTo(256);
        assertThatThrownBy(() -> tools.submitReviewResult(job.id(), page.range(), "checked", List.of())).hasMessageContaining("REVIEW_RESULT_INVALID");
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
