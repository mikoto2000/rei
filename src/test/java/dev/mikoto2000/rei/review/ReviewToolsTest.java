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

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {"valid", "unknown", "wrongPath", "wrongQuote", "blank", "foreign"})
  void corroboratedDismissalPreservesEvidenceCoverageAndIsScopedAndIdempotent(String kind)
      throws Exception {
    Files.writeString(dir.resolve("lesson.md"), "source evidence\n");
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
      tools.submitReviewPage(
          job.id(),
          "lesson.md",
          page.offset(),
          page.nextOffset(),
          "Reviewed",
          List.of(
              new ReviewTools.PageFinding(
                  "F1", "P2", "source evidence", "Original alleged defect")));
      String reason = "Rendered output and navigation metadata confirm the original is correct";
      org.assertj.core.api.ThrowableAssert.ThrowingCallable dismiss =
          () ->
              tools.dismissReviewFinding(
                  job.id(),
                  kind.equals("unknown") ? "F2" : "F1",
                  kind.equals("wrongPath") ? "other.md" : "lesson.md",
                  kind.equals("blank") ? " " : reason,
                  kind.equals("wrongQuote") ? "invented" : null);
      if (kind.equals("valid")) {
        var progress = new dev.mikoto2000.rei.core.stagnation.ProgressEvaluator(dir);
        var fullHistory = new java.util.HashSet<String>();
        for (int i = 0; i < 1024; i++) fullHistory.add(String.format("%064x", i));
        progress.restoreInformation(
            new dev.mikoto2000.rei.core.stagnation.InformationLedger.State(
                List.of(), fullHistory, false));
        var beforeDismissal = progress.beforeTool("dismissReviewFinding", "{}");
        var result = tools.dismissReviewFinding(job.id(), "F1", "lesson.md", reason, null);
        assertThat(result.changed()).isTrue();
        String receipt = StorageDatabase.JSON.writeValueAsString(result);
        assertThat(progress.afterTool("dismissReviewFinding", "{}", receipt, beforeDismissal))
            .extracting(dev.mikoto2000.rei.core.stagnation.ProgressEvidence::kind)
            .containsExactly(dev.mikoto2000.rei.core.stagnation.ProgressEvent.STATE_CHANGED);
        assertThat(progress.informationSnapshot().identities()).hasSize(1024);
        var repeated = tools.dismissReviewFinding(job.id(), "F1", "lesson.md", "Repeat", null);
        assertThat(repeated.changed()).isFalse();
        assertThat(
                progress.afterTool(
                    "dismissReviewFinding",
                    "{}",
                    StorageDatabase.JSON.writeValueAsString(repeated),
                    beforeDismissal))
            .isEmpty();
        var restored = new dev.mikoto2000.rei.core.stagnation.ProgressEvaluator(dir);
        restored.restoreInformation(progress.informationSnapshot());
        restored.restore(progress.snapshot());
        assertThat(
                restored.afterTool(
                    "dismissReviewFinding",
                    "{}",
                    receipt,
                    restored.beforeTool("dismissReviewFinding", "{}")))
            .isEmpty();
        var finding = result.findings().getFirst();
        assertThat(finding.verificationStatus()).isEqualTo("DISMISSED");
        assertThat(finding.evidence()).isEqualTo("source evidence");
        assertThat(finding.assessment()).contains("Original alleged defect", reason);
        assertThat(result.status()).isEqualTo("COMPLETE");
        assertThat(
                tools.dismissReviewFinding(job.id(), "F1", "lesson.md", "Repeat", null).revision())
            .isEqualTo(result.revision());
        Files.writeString(dir.resolve("lesson.md"), "source evidence\nchanged\n");
        assertThat(tools.reviewStatus(job.id()).status()).isEqualTo("STALE_VERSION");
        assertThat(tools.reviewFindings(job.id(), 0, 5).findings().getFirst().verificationStatus())
            .isEqualTo("DISMISSED");
        var rebuilt = tools.restartReview(job.id());
        assertThat(rebuilt.findings().getFirst().verificationStatus()).isEqualTo("DISMISSED");
        assertThat(rebuilt.ranges().getFirst().stale()).isTrue();
      } else {
        if (kind.equals("foreign")) {
          try (var other =
              AgentRunScope.open(new AgentRunContext("foreign", "chat", dir, "project"))) {
            assertThatThrownBy(dismiss).hasMessage("REVIEW_ACCESS_DENIED");
          }
        } else assertThatThrownBy(dismiss).isInstanceOf(ReviewResultRejectedException.class);
        assertThat(tools.reviewStatus(job.id()).status()).isEqualTo("COMPLETE");
        assertThat(tools.reviewStatus(job.id()).findings().getFirst().verificationStatus())
            .isEqualTo("VERIFIED");
      }
    }
  }

  @Test
  void pageReceiptDoesNotRepeatTheGrowingReviewLedger() throws Exception {
    var paths = new java.util.ArrayList<String>();
    for (int i = 0; i < 40; i++) {
      paths.add("lesson-" + i + ".md");
      Files.writeString(dir.resolve(paths.getLast()), "source\n");
    }
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
      var job = tools.beginReview(paths, "correctness", "v1");
      for (int i = 0; i < paths.size(); i++) {
        var target = job.targets().get(i);
        var page = tools.readReviewFile(job.id(), target.path(), target.version(), 0, 512);
        tools.confirmed(owner, List.of(page));
        var receipt =
            tools.submitReviewPage(
                job.id(),
                target.path(),
                page.offset(),
                page.nextOffset(),
                "assessment-" + i,
                List.of(new ReviewTools.PageFinding("F" + i, "P2", "source", "candidate-" + i)));
        assertThat(receipt.accepted()).isTrue();
        assertThat(receipt.ranges()).hasSize(1);
        assertThat(receipt.ranges().getFirst().range()).isEqualTo(page.range());
        assertThat(receipt.reviewedRanges()).isEqualTo(i + 1);
        assertThat(StorageDatabase.JSON.writeValueAsString(receipt).length()).isLessThan(2048);
      }
      var saved = tools.reviewStatus(job.id());
      assertThat(saved.status()).isEqualTo("COMPLETE");
      assertThat(saved.targets()).hasSize(40);
      assertThat(saved.ranges()).hasSize(40);
      assertThat(saved.ranges().getFirst().assessment()).isEqualTo("assessment-0");
      var candidates = tools.reviewFindings(job.id(), 0, 5);
      assertThat(candidates.status()).isEqualTo("COMPLETE");
      assertThat(candidates.totalFindings()).isEqualTo(40);
      assertThat(candidates.nextOffset()).isEqualTo(5);
      assertThat(candidates.findings())
          .extracting(ReviewCoverageLedger.Finding::id)
          .containsExactly("F0", "F1", "F2", "F3", "F4");
      var candidateJson = StorageDatabase.JSON.writeValueAsString(candidates);
      assertThat(candidateJson).doesNotContain("targets", "ranges", "assessment-0");
      assertThat(candidateJson.length()).isLessThan(4096);
      assertThat(tools.reviewFindings(job.id(), 5, Integer.MAX_VALUE).findings()).hasSize(10);
      assertThat(tools.reviewFindings(job.id(), 39, 5).nextOffset()).isNull();
      assertThat(tools.reviewFindings(job.id(), Integer.MAX_VALUE, 5).findings()).isEmpty();
      assertThat(tools.continuationSummary(owner))
          .contains(job.id(), "reviewFindings", "findingJobs");
      try (var foreign =
          AgentRunScope.open(new AgentRunContext("foreign", "chat", dir, "project"))) {
        assertThatThrownBy(() -> tools.reviewFindings(job.id(), 0, 5))
            .hasMessage("REVIEW_ACCESS_DENIED");
      }
      Files.writeString(dir.resolve(paths.getFirst()), "source changed\n");
      assertThat(tools.reviewFindings(job.id(), 0, 5).status()).isEqualTo("STALE_VERSION");
      assertThat(tools.reviewFindings(job.id(), 0, 5).findings().getFirst().verificationStatus())
          .isEqualTo("STALE");
      assertThat(tools.reviewFindings(job.id(), 1, 5).findings().getFirst().verificationStatus())
          .isEqualTo("VERIFIED");
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {"valid", "undelivered", "wrongEnd", "stale", "invented", "ambiguous", "foreign"})
  void pageSubmissionUsesOnlyTheCanonicalDeliveredRangeAndUniqueSourceQuotes(String kind)
      throws Exception {
    String source =
        "alpha\nissue unique\nomega\n" + (kind.equals("ambiguous") ? "issue unique\n" : "");
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
      var page =
          tools.readReviewFile(job.id(), "lesson.md", job.targets().getFirst().version(), 0, 512);
      if (!kind.equals("undelivered")) tools.confirmed(owner, List.of(page));
      if (kind.equals("stale")) Files.writeString(dir.resolve("lesson.md"), source + "changed");
      var findings =
          List.of(
              new ReviewTools.PageFinding(
                  "F1",
                  "P2",
                  kind.equals("invented") ? "not in source" : "issue unique",
                  "Explain this issue"));
      org.assertj.core.api.ThrowableAssert.ThrowingCallable submit =
          () ->
              tools.submitReviewPage(
                  job.id(),
                  "lesson.md",
                  page.offset(),
                  page.nextOffset() - (kind.equals("wrongEnd") ? 1 : 0),
                  "Checked this source page",
                  findings);
      if (kind.equals("valid")) {
        tools.submitReviewPage(
            job.id(),
            "lesson.md",
            page.offset(),
            page.nextOffset(),
            "Checked this source page",
            findings);
        var result = tools.reviewStatus(job.id());
        assertThat(result.status()).isEqualTo("COMPLETE");
        assertThat(result.ranges().getFirst().range()).isEqualTo(page.range());
        var finding = result.findings().getFirst();
        assertThat(finding.location().start()).isEqualTo(6);
        assertThat(finding.location().startLine()).isEqualTo(2);
        assertThat(finding.location().startOffset()).isZero();
        assertThat(finding.version()).isEqualTo(page.range().version());
        assertThat(source.substring(finding.location().start(), finding.location().end()))
            .isEqualTo(finding.evidence());
      } else {
        if (kind.equals("foreign")) {
          try (var other =
              AgentRunScope.open(new AgentRunContext("foreign", "chat", dir, "project"))) {
            assertThatThrownBy(submit)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("REVIEW_ACCESS_DENIED");
          }
        } else assertThatThrownBy(submit).isInstanceOf(ReviewResultRejectedException.class);
        assertThat(tools.reviewStatus(job.id()).ranges()).noneMatch(e -> e.reviewed());
        assertThat(tools.reviewStatus(job.id()).findings()).isEmpty();
      }
    }
  }

  /** 変更内容のレビューで、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void unnecessaryRestartPreservesCoverageAndReturnsNextOperation(boolean complete)
      throws Exception {
    Files.writeString(dir.resolve("lesson.md"), "unchanged text");
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
      if (complete) tools.submitReviewResult(job.id(), page.range(), "checked", List.of());
      var before = ledger.get(job.id(), ReviewTools.scope(owner));
      assertThatThrownBy(() -> tools.restartReview(job.id()))
          .isInstanceOf(ReviewResultRejectedException.class)
          .hasMessageContaining("No restart was performed")
          .hasMessageContaining("current status=" + before.status())
          .hasMessageContaining(complete ? "reviewFindings" : "reviewPending");
      assertThat(ledger.get(job.id(), ReviewTools.scope(owner))).isEqualTo(before);
    }
  }

  /** 版変更をrestartReview自身が検出し、旧版の評価を再利用しないことを確認する。 */
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
      // Restart itself must detect the change, without a preceding reviewStatus/read call.
      assertThat(ledger.get(job.id(), ReviewTools.scope(owner)).status()).isEqualTo("COMPLETE");
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

  @Test
  void resumedLedgerRetainsAssessmentsAndHandsOffOnlyMissingOffsets() throws Exception {
    Files.writeString(dir.resolve("lesson.md"), "line with evidence\n".repeat(120));
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
    var old = new AgentRunContext("old", "chat", dir, "project");
    var next = new AgentRunContext("next", "chat", dir, "project");
    String id;
    int nextOffset;
    try (var scope = AgentRunScope.open(old)) {
      var job = tools.beginReview(List.of("lesson.md"), "correctness", "v1");
      id = job.id();
      var page = tools.readReviewFile(id, "lesson.md", job.targets().getFirst().version(), 0, 256);
      nextOffset = page.nextOffset();
      tools.confirmed(old, List.of(page));
      tools.submitReviewResult(id, page.range(), "Checked this delivered range", List.of());
    }
    ledger.resume(id, ReviewTools.scope(old), ReviewTools.scope(next));
    try (var scope = AgentRunScope.open(next)) {
      var summary = StorageDatabase.JSON.readTree(tools.continuationSummary(next));
      assertThat(summary.path("incompleteJobs").asInt()).isEqualTo(1);
      var saved = summary.path("savedPendingSample").get(0);
      assertThat(saved.path("reviewJobId").asText()).isEqualTo(id);
      assertThat(saved.path("reviewedRanges").asInt()).isEqualTo(1);
      assertThat(saved.path("pendingSample").get(0).path("start").asInt()).isEqualTo(nextOffset);
      assertThat(tools.continuationSummary(next).length()).isLessThan(4096);
      assertThat(tools.reviewStatus(id).ranges().getFirst().reviewed()).isTrue();
    }
    assertThat(
            StorageDatabase.JSON
                .readTree(
                    tools.continuationSummary(
                        new AgentRunContext("unrelated", "chat", dir, "project")))
                .path("registeredJobs")
                .asInt())
        .isZero();
  }
}
