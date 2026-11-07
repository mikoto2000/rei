package dev.mikoto2000.rei.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import dev.mikoto2000.rei.core.Tools;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.AgentRunScope;
import dev.mikoto2000.rei.core.contextbudget.ContextBudgetManager;
import dev.mikoto2000.rei.core.contextbudget.ContextCompressionProperties;
import dev.mikoto2000.rei.core.contextbudget.RawToolResultStore;
import dev.mikoto2000.rei.core.contextbudget.ResultDelivery;
import dev.mikoto2000.rei.core.contextbudget.TokenEstimator;
import dev.mikoto2000.rei.core.contextbudget.ToolResultCompressor;
import dev.mikoto2000.rei.storage.StorageDatabase;
import dev.mikoto2000.rei.storage.StorageMigrationCoordinator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReviewReadingEfficiencyTest {
  @TempDir Path temporary;

  @Test
  void resumesLegacyCoverageThroughIdsWithoutRepeatingSavedAssessmentsOrLosingFindings() throws Exception {
    Path root = Files.createDirectory(temporary.resolve("legacy-repo"));
    String lesson = "# Lesson\n\n" + "本文とコードをまとめて確認する。\n\n```java\nclass Example {}\n```\n\n".repeat(100) + "Unique final evidence.\n";
    Files.writeString(root.resolve("lesson.md"), lesson);
    Path data = temporary.resolve("legacy-data");
    try (var migration = new StorageMigrationCoordinator(data)) { migration.prepare(); }
    var ledger = new ReviewCoverageLedger(new StorageDatabase(data));
    var reviews = new ReviewTools(ledger, new RawToolResultStore(data), new Tools(), TokenEstimator.conservative());
    var before = new AgentRunContext("before", "session", root, "project");
    String id;
    int offset;
    {
      var scope = AgentRunScope.open(before);
      try (scope) {
        var job = reviews.beginReview(List.of("lesson.md"), "correctness", "v1");
        id = job.id();
        var first = reviews.readReviewFile(id, "lesson.md", job.targets().getFirst().version(), 0, 512);
        offset = first.nextOffset();
        reviews.confirmed(before, List.of(first));
        reviews.submitReviewPage(id, "lesson.md", 0, offset, "saved assessment",
            List.of(new ReviewTools.PageFinding("F1", "P2", "# Lesson", "saved finding")));
        ledger.stop(id, ReviewTools.scope(before), true);
      }
    }
    var resumed = new AgentRunContext("after", "session", root, "project");
    ledger.resume(id, ReviewTools.scope(before), ReviewTools.scope(resumed));
    var scope = AgentRunScope.open(resumed);
    try (scope) {
      var page = reviews.readNextReviewWorkItem("legacy:" + id, null);
      assertThat(page.offset()).isEqualTo(offset);
      assertThat(page.content()).isEqualTo(lesson.substring(offset));
      assertThat(page.workItemId()).startsWith("legacy:" + id + ":");
      assertThatThrownBy(() -> reviews.submitReviewWorkItem(page.workItemId(), "wrong", "new assessment", List.of()))
          .isInstanceOf(RuntimeException.class);
      assertThatThrownBy(() -> reviews.submitReviewWorkItem(page.workItemId(), page.attempt(), "new assessment", List.of()))
          .isInstanceOf(RuntimeException.class);
      reviews.confirmed(resumed, List.of(page));
      var result = reviews.submitReviewWorkItem(page.workItemId(), page.attempt(), "new assessment",
          List.of(new dev.mikoto2000.rei.durable.WorkItemStore.Finding("F2", "P2", -10, -9, "Unique final evidence.", "new candidate")));
      assertThat(result.legacyReceipt()).containsEntry("accepted", true);
      assertThat(result.body().findings().getFirst().start()).isEqualTo(lesson.indexOf("Unique final evidence."));
      assertThat(reviews.reviewStatus(id).status()).isEqualTo("COMPLETE");
      assertThat(reviews.reviewStatus(id).findings()).extracting(ReviewCoverageLedger.Finding::id).containsExactly("F1", "F2");
      assertThat(reviews.reviewStatus(id).ranges()).filteredOn(ReviewCoverageLedger.Entry::reviewed)
          .extracting(ReviewCoverageLedger.Entry::assessment).containsExactly("saved assessment", "new assessment");
      assertThat(reviews.readNextReviewWorkItem("legacy:" + id, null)).isNull();
      reviews.requireComplete(resumed);
    }
  }

  @Test
  void largeContextReviewsWholeLessonsByIdWithFarFewerReadsAndRetainsDeliveryChecks() throws Exception {
    Path root = Files.createDirectory(temporary.resolve("repo"));
    String lesson = "# Lesson\n\n" + "説明と前提条件をまとめて確認する。\n\n```java\nclass Example {}\n```\n\n".repeat(500);
    Files.writeString(root.resolve("lesson.md"), lesson);
    Path data = temporary.resolve("data");
    try (var migration = new StorageMigrationCoordinator(data)) { migration.prepare(); }
    var reviews = new ReviewTools(new ReviewCoverageLedger(new StorageDatabase(data)),
        new RawToolResultStore(data), new Tools(), TokenEstimator.conservative());
    var context = new ContextCompressionProperties();
    context.setModelContextLimit(800000);
    context.setHardLimit(760000);
    context.setThreshold(680000);
    reviews.configureReading(
        prompt -> ContextBudgetManager.inputLimit(context, TokenEstimator.conservative(), prompt),
        100000);
    var owner = new AgentRunContext("run", "session", root, "project");
    {
      var active = AgentRunScope.open(owner);
      try (active) {
        var task = reviews.beginRepositoryReview("WORKTREE", List.of(), 100, 1048576L);
        reviews.scanReviewSnapshot(task.id(), 100);
        var page = reviews.readNextReviewWorkItem(task.id(), null);
        assertThat(page.content()).isEqualTo(lesson);
        assertThat(page.workItemId()).isNotBlank();
        assertThat(page.attempt()).isNotBlank();
        String wire = StorageDatabase.JSON.writeValueAsString(page);
        assertThat(TokenEstimator.conservative().text(wire)).isGreaterThan(16000);
        assertThat(StorageDatabase.JSON.readValue(wire, ReviewTools.Page.class)).isEqualTo(page);
        assertThat(ReviewTools.receipt(wire).job()).isEqualTo(page.reviewJobId());
        // Registered large review pages remain exact through context compression.
        var compressor = new ToolResultCompressor(new RawToolResultStore(data), TokenEstimator.conservative(), 8000, 2000);
        var message = org.springframework.ai.chat.messages.ToolResponseMessage.builder()
            .responses(List.of(new org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse("read", "readNextReviewWorkItem", wire)))
            .metadata(Map.of("rei.reviewReceipts", Map.of("read", ReviewTools.receipt(wire)),
                "rei.deliveryPolicies", Map.of("read", ResultDelivery.Kind.FILE_CONTENT))).build();
        assertThat(compressor.compact(message, "session", "run")).isEqualTo(message);
        assertThatThrownBy(() -> reviews.submitReviewWorkItem(page.workItemId(), page.attempt(), "checked", List.of()))
            .hasMessageContaining("SOURCE_NOT_DELIVERED");
        reviews.confirmed(owner, List.of(page));
        reviews.submitReviewWorkItem(page.workItemId(), page.attempt(), "checked", List.of());
        assertThat(reviews.reviewTaskStatus(task.id()).state()).isEqualTo("COMPLETE");
        reviews.requireComplete(owner);
        assertThat(reviews.readNextReviewWorkItem(task.id(), null)).isNull();
      }
    }
    // Same immutable lesson through the previous 2048-token acquisition budget.
    int oldReads = 0;
    var active = AgentRunScope.open(new AgentRunContext("baseline", "baseline", root, "project"));
    try (active) {
      var task = reviews.beginRepositoryReview("WORKTREE", List.of(), 100, 1048576L);
      reviews.scanReviewSnapshot(task.id(), 100);
      var joined = new StringBuilder();
      ReviewTools.Page page;
      while ((page = reviews.readNextReviewWorkItem(task.id(), 2048)) != null) {
        oldReads++;
        joined.append(page.content());
        reviews.confirmed(AgentRunScope.current(), List.of(page));
        reviews.submitReviewWorkItem(page.workItemId(), page.attempt(), "checked", List.of());
        if (reviews.reviewTaskStatus(task.id()).state().equals("COMPLETE")) break;
      }
      assertThat(joined.toString()).isEqualTo(lesson);
      assertThat(oldReads).isGreaterThan(5);
    }
    System.out.println("review_acquisition_comparison baseline2048=" + oldReads + " automatic800k=1");
  }
}
