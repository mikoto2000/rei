package dev.mikoto2000.rei.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mikoto2000.rei.core.Tools;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.AgentRunScope;
import dev.mikoto2000.rei.core.contextbudget.DeliveryToolRegistration;
import dev.mikoto2000.rei.core.contextbudget.RawToolResultStore;
import dev.mikoto2000.rei.core.contextbudget.ResultDelivery;
import dev.mikoto2000.rei.core.contextbudget.TokenEstimator;
import dev.mikoto2000.rei.core.contextbudget.ToolResultCompressor;
import dev.mikoto2000.rei.durable.WorkItemStore;
import dev.mikoto2000.rei.storage.StorageDatabase;
import dev.mikoto2000.rei.storage.StorageMigrationCoordinator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.prompt.Prompt;

@Tag("integration")
class ReviewBatchTest {
  @TempDir Path temporary;

  private record Fixture(
      ReviewTools tools,
      ReviewCoverageLedger ledger,
      RawToolResultStore raw,
      AgentRunContext owner,
      String task) {}

  private Fixture fixture(List<String> contents) throws Exception {
    Path root = Files.createDirectory(temporary.resolve("repository"));
    for (int i = 0; i < contents.size(); i++)
      Files.writeString(root.resolve("file-" + i + ".md"), contents.get(i));
    Path data = temporary.resolve("data");
    try (var migration = new StorageMigrationCoordinator(data)) {
      migration.prepare();
    }
    var ledger = new ReviewCoverageLedger(new StorageDatabase(data));
    var raw = new RawToolResultStore(data);
    var tools = new ReviewTools(ledger, raw, new Tools(), TokenEstimator.conservative());
    var owner = new AgentRunContext("run", "session", root, "project");
    try (var scope = AgentRunScope.open(owner)) {
      String task = tools.beginRepositoryReview("WORKTREE", List.of(), 100, 1048576L).id();
      tools.scanReviewSnapshot(task, 100);
      return new Fixture(tools, ledger, raw, owner, task);
    }
  }

  @Test
  void realJsonBatchKeepsEveryReceiptAndRequiresUnmodifiedModelDelivery() throws Exception {
    var f = fixture(List.of("first🙂", "second日本語", "third", "fourth", "fifth"));
    try (var scope = AgentRunScope.open(f.owner())) {
      var callback =
          java.util.Arrays.stream(DeliveryToolRegistration.provider(f.tools()).getToolCallbacks())
              .filter(c -> c.getToolDefinition().name().equals("readNextReviewWorkItems"))
              .findFirst()
              .orElseThrow();
      assertThat(((DeliveryToolRegistration.Registered) callback).reviewPage()).isTrue();
      String wire =
          callback.call(StorageDatabase.JSON.writeValueAsString(Map.of("taskId", f.task())));
      var pages = StorageDatabase.JSON.readValue(wire, ReviewTools.Page[].class);
      assertThat(pages).hasSize(4);
      for (var page : pages)
        assertThatThrownBy(() ->
                f.tools()
                    .submitReviewWorkItem(page.workItemId(), page.attempt(), "checked", List.of()))
            .hasMessageContaining("SOURCE_NOT_DELIVERED");
      var message =
          ToolResponseMessage.builder()
              .responses(
                  List.of(
                      new ToolResponseMessage.ToolResponse(
                          "batch", "readNextReviewWorkItems", wire)))
              .metadata(
                  Map.of(
                      "rei.reviewReceipts",
                      Map.of("batch", ReviewTools.receipt(wire)),
                      "rei.deliveryPolicies",
                      Map.of("batch", ResultDelivery.Kind.FILE_CONTENT)))
              .build();
      var compressor = new ToolResultCompressor(f.raw(), TokenEstimator.conservative(), 8000, 2000);
      assertThat(compressor.compact(message, "session", "run")).isEqualTo(message);
      var tampered =
          ToolResponseMessage.builder()
              .responses(
                  List.of(
                      new ToolResponseMessage.ToolResponse(
                          "batch", "readNextReviewWorkItems", wire.replace("first", "other"))))
              .metadata(message.getMetadata())
              .build();
      f.tools().confirmedPrompt(new Prompt(List.of(tampered)), f.owner());
      assertThatThrownBy(() ->
              f.tools()
                  .submitReviewWorkItem(
                      pages[0].workItemId(), pages[0].attempt(), "checked", List.of()))
          .hasMessageContaining("SOURCE_NOT_DELIVERED");
      f.tools().confirmedPrompt(new Prompt(List.of(message)), f.owner());
      for (var page : pages)
        f.tools().submitReviewWorkItem(page.workItemId(), page.attempt(), "checked", List.of());
      assertThat(f.tools().listReviewTaskResults(f.task(), null, 10).entries()).hasSize(4);
      assertThat(f.tools().reviewTaskStatus(f.task()).state()).isEqualTo("ACTIVE");
      assertThat(f.tools().readNextReviewWorkItems(f.task(), null, null))
          .singleElement()
          .satisfies(page -> assertThat(page.content()).isEqualTo("fifth"));
    }
  }

  @Test
  void lostAndPartiallySubmittedBatchesRecoverBeforeNewAcquisitionAndResumeRevokesAttempts()
      throws Exception {
    var f = fixture(List.of("first", "second", "third", "fourth", "fifth"));
    List<ReviewTools.Page> batch;
    try (var scope = AgentRunScope.open(f.owner())) {
      batch = f.tools().readNextReviewWorkItems(f.task(), null, null);
      var reopened =
          new ReviewTools(f.ledger(), f.raw(), new Tools(), TokenEstimator.conservative());
      assertThat(reopened.readNextReviewWorkItems(f.task(), null, null)).isEqualTo(batch);
      f.tools().confirmed(f.owner(), batch);
      var first = batch.getFirst();
      f.tools().submitReviewWorkItem(first.workItemId(), first.attempt(), "checked", List.of());
      assertThat(reopened.readNextReviewWorkItems(f.task(), null, null))
          .containsExactlyElementsOf(batch.subList(1, 4));
    }
    var resumed = new AgentRunContext("next-run", "session", f.owner().projectRoot(), "project");
    try (var scope = AgentRunScope.open(resumed)) {
      assertThatThrownBy(() -> f.tools().readNextReviewWorkItems(f.task(), null, null))
          .hasMessageContaining("TASK_RUN_NOT_BOUND");
      f.tools().resumeRepositoryReview(f.task());
      var next = f.tools().readNextReviewWorkItems(f.task(), null, null);
      assertThat(next).hasSize(4);
      assertThat(next).noneMatch(page -> page.workItemId().equals(batch.getFirst().workItemId()));
      assertThat(next.getFirst().attempt()).isNotEqualTo(batch.get(1).attempt());
      assertThatThrownBy(() ->
              f.tools()
                  .submitReviewWorkItem(
                      batch.get(1).workItemId(), batch.get(1).attempt(), "checked", List.of()))
          .hasMessageContaining("STALE_CLAIM");
    }
    try (var scope = AgentRunScope.open(f.owner())) {
      assertThatThrownBy(() -> f.tools().readNextReviewWorkItems(f.task(), null, null))
          .hasMessageContaining("TASK_RUN_NOT_BOUND");
    }
    try (var scope =
        AgentRunScope.open(
            new AgentRunContext("foreign", "other", f.owner().projectRoot(), "project"))) {
      assertThatThrownBy(() -> f.tools().readNextReviewWorkItems(f.task(), null, null))
          .hasMessageContaining("TASK_ACCESS_DENIED");
    }
  }

  @Test
  void sharedBudgetPreservesEveryCharacterOfLongSource() throws Exception {
    String source = "# Heading\n\n説明🙂とコード\n\n".repeat(150);
    var f = fixture(List.of(source, "small", "another"));
    try (var scope = AgentRunScope.open(f.owner())) {
      var joined = new StringBuilder();
      while (!f.tools().reviewTaskStatus(f.task()).state().equals("COMPLETE")) {
        var batch = f.tools().readNextReviewWorkItems(f.task(), 4, 512);
        assertThat(
                TokenEstimator.conservative().text(StorageDatabase.JSON.writeValueAsString(batch)))
            .isLessThanOrEqualTo(512);
        assertThat(batch).isNotEmpty();
        for (var page : batch)
          if (page.range().path().equals("file-0.md")) joined.append(page.content());
        f.tools().confirmed(f.owner(), batch);
        for (var page : batch)
          f.tools().submitReviewWorkItem(page.workItemId(), page.attempt(), "checked", List.of());
      }
      assertThat(joined.toString()).isEqualTo(source);
      assertThat(f.tools().readNextReviewWorkItems(f.task(), 4, 512)).isEmpty();
      f.tools().requireComplete(f.owner());
    }
  }

  @Test
  void smallerReplayBudgetRejectsWithoutSplittingOrLosingTheFrozenClaims() throws Exception {
    var f = fixture(List.of("source🙂".repeat(60), "other🙂".repeat(60)));
    try (var scope = AgentRunScope.open(f.owner())) {
      var original = f.tools().readNextReviewWorkItems(f.task(), 4, 4096);
      assertThat(original).hasSize(2);
      assertThatThrownBy(() -> f.tools().readNextReviewWorkItems(f.task(), 4, 256))
          .hasMessageContaining("REVIEW_BATCH_BUDGET_TOO_SMALL");
      assertThat(f.tools().readNextReviewWorkItems(f.task(), 4, 4096)).isEqualTo(original);
      assertThatThrownBy(() -> f.tools().readNextReviewWorkItems(f.task(), 0, null))
          .hasMessageContaining("INVALID_REVIEW_BATCH_ARGUMENTS");
    }
  }

  @Test
  void explicitCoordinatesDisambiguateButPartialOrSurrogateSplittingCoordinatesAreRejected()
      throws Exception {
    String source = "🙂 repeated repeated";
    var f = fixture(List.of(source));
    try (var scope = AgentRunScope.open(f.owner())) {
      var page = f.tools().readNextReviewWorkItem(f.task(), null);
      f.tools().confirmed(f.owner(), List.of(page));
      var partial = new WorkItemStore.Finding("f", "P2", 0, null, "repeated", "issue");
      assertThatThrownBy(() ->
              f.tools()
                  .submitReviewWorkItem(
                      page.workItemId(), page.attempt(), "checked", List.of(partial)))
          .hasMessageContaining("REVIEW_RESULT_INVALID");
      var split = new WorkItemStore.Finding("f", "P2", 1, source.length(), "repeated", "issue");
      assertThatThrownBy(() ->
              f.tools()
                  .submitReviewWorkItem(
                      page.workItemId(), page.attempt(), "checked", List.of(split)))
          .hasMessageContaining("REVIEW_RESULT_INVALID");
      int start = source.lastIndexOf("repeated");
      var exact =
          new WorkItemStore.Finding(
              "f", "P2", start, start + "repeated".length(), "repeated", "issue");
      assertThat(
              f.tools()
                  .submitReviewWorkItem(
                      page.workItemId(), page.attempt(), "checked", List.of(exact))
                  .body()
                  .findings())
          .containsExactly(exact);
    }
  }

  @Test
  void duplicateQuotesOutsideTheDeliveredChunkDoNotAffectLocation() throws Exception {
    var f = fixture(List.of("marker\n" + "safe text\n".repeat(500) + "marker\n"));
    try (var scope = AgentRunScope.open(f.owner())) {
      var page = f.tools().readNextReviewWorkItem(f.task(), 512);
      assertThat(page.truncated()).isTrue();
      f.tools().confirmed(f.owner(), List.of(page));
      var finding = new WorkItemStore.Finding("f", "P2", null, null, "marker", "issue");
      var result =
          f.tools()
              .submitReviewWorkItem(page.workItemId(), page.attempt(), "checked", List.of(finding));
      assertThat(result.body().findings().getFirst().start()).isZero();
    }
  }

  @Test
  void laterSizingFailureRollsBackAllNewClaims() throws Exception {
    var f = fixture(List.of("first", "second"));
    var work = new WorkItemStore(f.ledger().durableTasks());
    var scope =
        new dev.mikoto2000.rei.durable.DurableTaskStore.Scope(
            "project", "session", f.owner().projectRoot().toString());
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    assertThatThrownBy(() ->
            work.claimBatch(
                scope,
                f.task(),
                "run",
                4,
                (item, source, attempt) -> {
                  if (calls.incrementAndGet() == 2)
                    throw new IllegalArgumentException("sizing failure");
                  return item.end();
                }))
        .hasMessageContaining("sizing failure");
    assertThat(work.running(scope, f.task(), "run")).isNull();
    try (var active = AgentRunScope.open(f.owner())) {
      assertThat(f.tools().readNextReviewWorkItems(f.task(), null, null)).hasSize(2);
    }
  }

  @Test
  void hostLocatesFrozenUnicodeEvidenceRejectsAmbiguityAndDeduplicatesReplay() throws Exception {
    String frozen = "前提🙂\r\nrepeated repeated\nunique 根拠🙂 end";
    var f = fixture(List.of(frozen));
    try (var scope = AgentRunScope.open(f.owner())) {
      var page = f.tools().readNextReviewWorkItem(f.task(), null);
      var finding = new WorkItemStore.Finding("f", "P2", null, null, "unique 根拠🙂", "issue");
      assertThatThrownBy(() ->
              f.tools()
                  .submitReviewWorkItem(
                      page.workItemId(), page.attempt(), "checked", List.of(finding)))
          .hasMessageContaining("SOURCE_NOT_DELIVERED");
      f.tools().confirmed(f.owner(), List.of(page));
      Files.writeString(f.owner().projectRoot().resolve("file-0.md"), "changed live file");
      var repeated = new WorkItemStore.Finding("f", "P2", null, null, "repeated", "issue");
      assertThatThrownBy(() ->
              f.tools()
                  .submitReviewWorkItem(
                      page.workItemId(), page.attempt(), "checked", List.of(repeated)))
          .hasMessageContaining("REVIEW_EVIDENCE_AMBIGUOUS");
      var absent = new WorkItemStore.Finding("f", "P2", null, null, "absent", "issue");
      assertThatThrownBy(() ->
              f.tools()
                  .submitReviewWorkItem(
                      page.workItemId(), page.attempt(), "checked", List.of(absent)))
          .hasMessageContaining("REVIEW_EVIDENCE_NOT_FOUND");
      assertThat(f.tools().listReviewTaskResults(f.task(), null, 10).entries()).isEmpty();
      var result =
          f.tools()
              .submitReviewWorkItem(page.workItemId(), page.attempt(), "checked", List.of(finding));
      assertThat(result.body().findings())
          .singleElement()
          .satisfies(located -> {
            assertThat(located.start()).isEqualTo(frozen.indexOf(finding.evidence()));
            assertThat(located.end()).isEqualTo(located.start() + finding.evidence().length());
          });
      assertThat(
              f.tools()
                  .submitReviewWorkItem(
                      page.workItemId(), page.attempt(), "checked", List.of(finding)))
          .isEqualTo(result);
      assertThat(
              f.tools()
                  .submitReviewWorkItem(
                      page.workItemId(), page.attempt(), "checked", result.body().findings()))
          .isEqualTo(result);
    }
  }
}
