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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@org.junit.jupiter.api.Tag("integration")
class ReviewIndexedPageTest {
  @TempDir Path root;

  @Test
  void latePageAndFindingRetainExactCoordinatesAndBudget() throws Exception {
    String prefix = "line🙂\r\n".repeat(100000);
    String evidence = "unique source evidence";
    Files.writeString(
        root.resolve("source.md"), "\uFEFF" + prefix + evidence + "\n" + "next🙂\n".repeat(1000));
    int offset = 1 + prefix.length();
    var data = root.resolve("data");
    try (var migration = new StorageMigrationCoordinator(data)) {
      migration.prepare();
    }
    var tools =
        new ReviewTools(
            new ReviewCoverageLedger(new StorageDatabase(data)),
            new RawToolResultStore(data),
            new Tools(),
            TokenEstimator.conservative());
    var owner = new AgentRunContext("run", "chat", root, "project");
    try (var scope = AgentRunScope.open(owner)) {
      var job = tools.beginReview(List.of("source.md"), "correctness", "v1");
      var page =
          tools.readReviewFile(
              job.id(), "source.md", job.targets().getFirst().version(), offset, 512);
      assertThat(page.range().span().startLine()).isEqualTo(100001);
      assertThat(page.range().span().startOffset()).isZero();
      assertThat(page.content()).startsWith(evidence);
      assertThat(TokenEstimator.conservative().text(StorageDatabase.JSON.writeValueAsString(page)))
          .isLessThanOrEqualTo(512);
      tools.confirmed(owner, List.of(page));
      var result =
          tools.submitReviewPage(
              job.id(),
              "source.md",
              page.offset(),
              page.nextOffset(),
              "reviewed",
              List.of(new ReviewTools.PageFinding("finding", "LOW", evidence, "source verified")));
      assertThat(result.accepted()).isTrue();
      var finding = result.findings().getFirst();
      assertThat(finding.location().start()).isEqualTo(offset);
      assertThat(finding.location().startLine()).isEqualTo(100001);
      assertThat(finding.location().endLine()).isEqualTo(100001);
      assertThat(finding.location().startOffset()).isZero();
      assertThat(finding.location().endOffset()).isEqualTo(evidence.length());
    }
  }
}
