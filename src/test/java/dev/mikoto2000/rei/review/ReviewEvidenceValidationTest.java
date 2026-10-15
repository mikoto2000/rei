package dev.mikoto2000.rei.review;

import static org.assertj.core.api.Assertions.*;

import dev.mikoto2000.rei.core.*;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.core.contextbudget.*;
import dev.mikoto2000.rei.storage.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class ReviewEvidenceValidationTest {
  @TempDir Path dir;

  @Test
  void fabricatedLineCoordinatesCannotBecomeVerifiedFindings() throws Exception {
    Files.writeString(dir.resolve("source.md"), "first\nwrong fact\n");
    try (var migration = new StorageMigrationCoordinator(dir.resolve("data"))) {
      migration.prepare();
    }
    var tools =
        new ReviewTools(
            new ReviewCoverageLedger(new StorageDatabase(dir.resolve("data"))),
            new RawToolResultStore(dir.resolve("data")),
            new Tools(),
            TokenEstimator.conservative());
    var owner = new AgentRunContext("run", "chat", dir, "project");
    try (var scope = AgentRunScope.open(owner)) {
      var job = tools.beginReview(List.of("source.md"), "correctness", "v1");
      var page =
          tools.readReviewFile(job.id(), "source.md", job.targets().getFirst().version(), 0, 512);
      tools.confirmed(owner, List.of(page));
      var finding =
          new ReviewCoverageLedger.Finding(
              "fake",
              "HIGH",
              "source.md",
              page.range().version(),
              new ReviewCoverageLedger.Span(6, 16, 1, 1, 6, 16),
              "wrong fact",
              "incorrect",
              "VERIFIED");
      assertThatThrownBy(
              () -> tools.submitReviewResult(job.id(), page.range(), "checked", List.of(finding)))
          .hasMessageContaining("REVIEW_RESULT_INVALID");
    }
  }

  @Test
  @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
  void windowsCaseAliasesCannotCreateTwoTargetsForOneFile() throws Exception {
    Files.writeString(dir.resolve("MixedCase.md"), "source");
    try (var migration = new StorageMigrationCoordinator(dir.resolve("data"))) {
      migration.prepare();
    }
    var tools =
        new ReviewTools(
            new ReviewCoverageLedger(new StorageDatabase(dir.resolve("data"))),
            new RawToolResultStore(dir.resolve("data")),
            new Tools(),
            TokenEstimator.conservative());
    try (var scope = AgentRunScope.open(new AgentRunContext("run", "chat", dir, "project"))) {
      assertThatThrownBy(
              () -> tools.beginReview(List.of("MixedCase.md", "mixedcase.md"), "review", "v1"))
          .hasMessageContaining("REVIEW_RESULT_INVALID");
    }
  }

  @Test
  void findingsCannotSplitASurrogatePair() throws Exception {
    Files.writeString(dir.resolve("source.md"), "abc🙂def");
    try (var migration = new StorageMigrationCoordinator(dir.resolve("data"))) {
      migration.prepare();
    }
    var tools =
        new ReviewTools(
            new ReviewCoverageLedger(new StorageDatabase(dir.resolve("data"))),
            new RawToolResultStore(dir.resolve("data")),
            new Tools(),
            TokenEstimator.conservative());
    var owner = new AgentRunContext("run", "chat", dir, "project");
    try (var scope = AgentRunScope.open(owner)) {
      var job = tools.beginReview(List.of("source.md"), "review", "v1");
      var page =
          tools.readReviewFile(job.id(), "source.md", job.targets().getFirst().version(), 0, 512);
      tools.confirmed(owner, List.of(page));
      var finding =
          new ReviewCoverageLedger.Finding(
              "fake",
              "HIGH",
              "source.md",
              page.range().version(),
              new ReviewCoverageLedger.Span(3, 4, 1, 1, 3, 4),
              "abc🙂def".substring(3, 4),
              "broken quote",
              "VERIFIED");
      assertThatThrownBy(
              () -> tools.submitReviewResult(job.id(), page.range(), "checked", List.of(finding)))
          .hasMessageContaining("REVIEW_RESULT_INVALID");
    }
  }

  @Test
  void utf16BomAndCp932SourcesKeepExactDecodedCharacters() throws Exception {
    var files = new Tools();
    Files.write(
        dir.resolve("utf16.txt"),
        "\uFEFF本文🙂\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_16LE));
    Files.write(
        dir.resolve("cp932.txt"), "日本語\r\n".getBytes(java.nio.charset.Charset.forName("MS932")));
    try (var scope = AgentRunScope.open(new AgentRunContext("run", "chat", dir, "project"))) {
      assertThat(files.reviewSnapshot("utf16.txt").text()).isEqualTo("\uFEFF本文🙂\r\n");
      assertThat(files.reviewSnapshot("cp932.txt").text()).isEqualTo("日本語\r\n");
    }
  }
}
