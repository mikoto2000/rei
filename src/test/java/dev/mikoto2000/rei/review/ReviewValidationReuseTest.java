package dev.mikoto2000.rei.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("integration")
class ReviewValidationReuseTest {
  @TempDir Path root;

  private static final class CountingTools extends Tools {
    int snapshots, versions;
    java.util.function.Consumer<String> beforeVersion = ignored -> {};

    @Override
    public ReviewSnapshot reviewSnapshot(String path) throws java.io.IOException {
      snapshots++;
      return super.reviewSnapshot(path);
    }

    @Override
    public String reviewVersion(String path) throws java.io.IOException {
      versions++;
      beforeVersion.accept(path);
      return super.reviewVersion(path);
    }
  }

  @Test
  void submissionDecodesOnlyEvidenceAndStatusDoesNotDecodeOtherTargets() throws Exception {
    exercise(false);
  }

  @Test
  void changeBetweenValidationPassesRejectsTheSubmission() throws Exception {
    exercise(true);
  }

  @Test
  void pdfSubmissionExtractsTextOnceAndRechecksRawBytes() throws Exception {
    try (var pdf = new org.apache.pdfbox.pdmodel.PDDocument()) {
      var page = new org.apache.pdfbox.pdmodel.PDPage();
      pdf.addPage(page);
      try (var stream = new org.apache.pdfbox.pdmodel.PDPageContentStream(pdf, page)) {
        stream.beginText();
        stream.setFont(
            new org.apache.pdfbox.pdmodel.font.PDType1Font(
                org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA),
            12);
        stream.newLineAtOffset(30, 700);
        stream.showText("PDF source evidence");
        stream.endText();
      }
      pdf.save(root.resolve("source.pdf").toFile());
    }
    var data = root.resolve("data");
    try (var migration = new StorageMigrationCoordinator(data)) {
      migration.prepare();
    }
    var files = new CountingTools();
    var tools =
        new ReviewTools(
            new ReviewCoverageLedger(new StorageDatabase(data)),
            new RawToolResultStore(data),
            files,
            TokenEstimator.conservative());
    var owner = new AgentRunContext("run", "chat", root, "project");
    try (var scope = AgentRunScope.open(owner)) {
      var job = tools.beginReview(List.of("source.pdf"), "correctness", "v1");
      var page =
          tools.readReviewFile(job.id(), "source.pdf", job.targets().getFirst().version(), 0, 512);
      tools.confirmed(owner, List.of(page));
      files.snapshots = files.versions = 0;
      assertThat(
              tools
                  .submitReviewPage(
                      job.id(),
                      "source.pdf",
                      page.offset(),
                      page.nextOffset(),
                      "reviewed",
                      List.of())
                  .accepted())
          .isTrue();
      assertThat(files.snapshots).isEqualTo(1);
      assertThat(files.versions).isEqualTo(1);
    }
  }

  private void exercise(boolean changed) throws Exception {
    Files.writeString(root.resolve("a.md"), "source evidence\n");
    Files.writeString(root.resolve("b.md"), "other source\n");
    var data = root.resolve("data");
    try (var migration = new StorageMigrationCoordinator(data)) {
      migration.prepare();
    }
    var ledger = new ReviewCoverageLedger(new StorageDatabase(data));
    var files = new CountingTools();
    var tools =
        new ReviewTools(ledger, new RawToolResultStore(data), files, TokenEstimator.conservative());
    var owner = new AgentRunContext("run", "chat", root, "project");
    try (var scope = AgentRunScope.open(owner)) {
      var job = tools.beginReview(List.of("a.md", "b.md"), "correctness", "v1");
      var page = tools.readReviewFile(job.id(), "a.md", job.targets().getFirst().version(), 0, 512);
      tools.confirmed(owner, List.of(page));
      files.snapshots = files.versions = 0;
      if (changed)
        files.beforeVersion =
            path -> {
              if (path.equals("a.md")) {
                try {
                  Files.writeString(root.resolve(path), "changed source\n");
                } catch (java.io.IOException error) {
                  throw new java.io.UncheckedIOException(error);
                }
              }
            };
      if (changed) {
        assertThatThrownBy(
                () ->
                    tools.submitReviewPage(
                        job.id(), "a.md", page.offset(), page.nextOffset(), "reviewed", List.of()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("STALE_VERSION");
        assertThat(ledger.get(job.id(), job.owner()).ranges())
            .noneMatch(ReviewCoverageLedger.Entry::reviewed);
      } else {
        assertThat(
                tools
                    .submitReviewPage(
                        job.id(), "a.md", page.offset(), page.nextOffset(), "reviewed", List.of())
                    .accepted())
            .isTrue();
        assertThat(files.snapshots).isEqualTo(1);
        assertThat(files.versions).isEqualTo(3);
        files.snapshots = files.versions = 0;
        tools.reviewStatus(job.id());
        assertThat(files.snapshots).isZero();
        assertThat(files.versions).isEqualTo(2);
      }
    }
  }
}
