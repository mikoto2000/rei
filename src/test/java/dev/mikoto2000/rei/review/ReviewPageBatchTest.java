package dev.mikoto2000.rei.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mikoto2000.rei.core.Tools;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.AgentRunScope;
import dev.mikoto2000.rei.core.chat.ToolLoopSupport;
import dev.mikoto2000.rei.core.contextbudget.DeliveryToolRegistration;
import dev.mikoto2000.rei.core.contextbudget.RawToolResultStore;
import dev.mikoto2000.rei.core.contextbudget.TokenEstimator;
import dev.mikoto2000.rei.storage.StorageDatabase;
import dev.mikoto2000.rei.storage.StorageMigrationCoordinator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

@org.junit.jupiter.api.Tag("integration")
class ReviewPageBatchTest {
  @TempDir Path root;
  ReviewTools tools;
  ReviewCoverageLedger ledger;
  AgentRunContext owner;
  int snapshots;

  @BeforeEach
  void setup() throws Exception {
    Files.writeString(root.resolve("source.md"), "本文🙂\r\n".repeat(3000));
    var data = root.resolve("data");
    try (var migration = new StorageMigrationCoordinator(data)) {
      migration.prepare();
    }
    ledger = new ReviewCoverageLedger(new StorageDatabase(data));
    tools =
        new ReviewTools(
            ledger,
            new RawToolResultStore(data),
            new Tools() {
              @Override
              public ReviewSnapshot reviewSnapshot(String path) throws java.io.IOException {
                snapshots++;
                return super.reviewSnapshot(path);
              }
            },
            TokenEstimator.conservative());
    owner = new AgentRunContext("run", "chat", root, "project");
  }

  private ReviewCoverageLedger.Job begin() throws Exception {
    return tools.beginReview(List.of("source.md"), "correctness", "v1");
  }

  private List<ReviewTools.Page> read(ReviewCoverageLedger.Job job, int budget) throws Exception {
    return tools.readReviewPages(
        job.id(), "source.md", job.targets().getFirst().version(), 0, 4, budget);
  }

  private ReviewTools.PageAssessment assessment(ReviewTools.Page page, String text) {
    return new ReviewTools.PageAssessment(
        "source.md", page.offset(), page.nextOffset(), text, List.of());
  }

  @Test
  void invalidReadBoundsReturnCorrectionWithoutFetchingAnyPages() throws Exception {
    try (var scope = AgentRunScope.open(owner)) {
      var job = begin();
      for (var bounds :
          List.of(
              new int[] {0, 1024}, new int[] {5, 1024}, new int[] {1, 511}, new int[] {1, 2049})) {
        assertThatThrownBy(
                () ->
                    tools.readReviewPages(
                        job.id(),
                        "source.md",
                        job.targets().getFirst().version(),
                        0,
                        bounds[0],
                        bounds[1]))
            .isInstanceOf(dev.mikoto2000.rei.toolcontract.ToolReadRejectedException.class)
            .hasMessageContaining("1..4", "512..2048");
        assertThat(ledger.get(job.id(), job.owner())).isEqualTo(job);
      }
    }
  }

  @Test
  void fourFetchedPagesUseOneRevisionAndRemainUndelivered() throws Exception {
    try (var scope = AgentRunScope.open(owner)) {
      var job = begin();
      var pages = read(job, 2048);
      assertThat(pages).hasSize(4);
      var saved = ledger.get(job.id(), job.owner());
      assertThat(saved.revision()).isEqualTo(job.revision() + 1);
      assertThat(saved.ranges()).hasSize(4).noneMatch(e -> e.delivered() || e.reviewed());
      ledger.fetchedBatch(
          job.id(), job.owner(), pages.stream().map(ReviewTools.Page::range).toList());
      assertThat(ledger.get(job.id(), job.owner())).isEqualTo(saved);
    }
  }

  @Test
  void invalidLastFetchRollsBackEveryRange() throws Exception {
    try (var scope = AgentRunScope.open(owner)) {
      var job = begin();
      var span = new ReviewCoverageLedger.Span(0, 1, 1, 1, 0, 1);
      var valid =
          new ReviewCoverageLedger.Range(
              "source.md", job.targets().getFirst().version(), span, ReviewTools.hash("本"));
      var invalid =
          new ReviewCoverageLedger.Range(
              "outside.md", job.targets().getFirst().version(), span, ReviewTools.hash("本"));
      assertThatThrownBy(() -> ledger.fetchedBatch(job.id(), job.owner(), List.of(valid, invalid)))
          .isInstanceOf(IllegalArgumentException.class);
      assertThat(ledger.get(job.id(), job.owner())).isEqualTo(job);
      var saved = ledger.fetchedBatch(job.id(), job.owner(), List.of(valid, valid));
      assertThat(saved.revision()).isEqualTo(job.revision() + 1);
      assertThat(saved.ranges()).hasSize(1);
    }
  }

  @Test
  void batchUsesOneSnapshotAndRespectsWholeWireBudgetAndUnicode() throws Exception {
    try (var scope = AgentRunScope.open(owner)) {
      var job = begin();
      for (int budget : List.of(512, 1024, 2048)) {
        snapshots = 0;
        var pages = read(job, budget);
        assertThat(pages.size()).isBetween(1, 4);
        assertThat(snapshots).isEqualTo(1);
        assertThat(
                TokenEstimator.conservative().text(StorageDatabase.JSON.writeValueAsString(pages)))
            .isLessThanOrEqualTo(budget);
        int offset = 0;
        for (var page : pages) {
          assertThat(page.offset()).isEqualTo(offset);
          assertThat(page.nextOffset()).isGreaterThan(offset);
          assertThat(Character.isHighSurrogate(page.content().charAt(page.content().length() - 1)))
              .isFalse();
          offset = page.nextOffset();
        }
      }
      assertThatThrownBy(
              () ->
                  tools.readReviewPages(
                      job.id(), "source.md", job.targets().getFirst().version(), 0, 5, 2048))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void dispatcherReceiptConfirmsAllPagesOnlyAtSuccessfulDeliveryBoundary() throws Exception {
    try (var scope = AgentRunScope.open(owner)) {
      var job = begin();
      var options =
          OpenAiChatOptions.builder()
              .toolCallbacks(DeliveryToolRegistration.provider(tools).getToolCallbacks())
              .build();
      String args =
          StorageDatabase.JSON.writeValueAsString(
              Map.of(
                  "reviewJobId",
                  job.id(),
                  "path",
                  "source.md",
                  "expectedVersion",
                  job.targets().getFirst().version(),
                  "offset",
                  0,
                  "maxPages",
                  4,
                  "totalTokenBudget",
                  2048));
      var assistant =
          AssistantMessage.builder()
              .toolCalls(
                  List.of(
                      new AssistantMessage.ToolCall("call", "function", "readReviewPages", args)))
              .build();
      var dispatched =
          new ToolLoopSupport()
              .execute(
                  new Prompt(List.of(new UserMessage("review")), options),
                  new ChatResponse(List.of(new Generation(assistant))));
      var fetched = ledger.get(job.id(), job.owner());
      assertThat(fetched.ranges()).hasSize(4).noneMatch(ReviewCoverageLedger.Entry::delivered);
      var pages =
          fetched.ranges().stream()
              .map(
                  e ->
                      new ReviewTools.PageAssessment(
                          "source.md",
                          e.range().span().start(),
                          e.range().span().end(),
                          "reviewed",
                          List.<ReviewTools.PageFinding>of()))
              .toList();
      assertThatThrownBy(() -> tools.submitReviewPages(job.id(), pages))
          .isInstanceOf(ReviewResultRejectedException.class);
      var history = new ArrayList<>(dispatched.conversationHistory());
      var message = (ToolResponseMessage) history.getLast();
      var response = message.getResponses().getFirst();
      history.set(
          history.size() - 1,
          ToolResponseMessage.builder()
              .responses(
                  List.of(
                      new ToolResponseMessage.ToolResponse(response.id(), response.name(), "[]")))
              .metadata(message.getMetadata())
              .build());
      tools.confirmedPrompt(new Prompt(history, options), owner);
      assertThat(ledger.get(job.id(), job.owner()).ranges())
          .noneMatch(ReviewCoverageLedger.Entry::delivered);
      tools.confirmedPrompt(new Prompt(dispatched.conversationHistory(), options), owner);
      assertThat(ledger.get(job.id(), job.owner()).ranges())
          .allMatch(ReviewCoverageLedger.Entry::delivered);
      var result = tools.submitReviewPages(job.id(), pages);
      assertThat(result).hasSize(4).allMatch(ReviewTools.PageSubmission::accepted);
    }
  }

  @Test
  void oversizedBatchSavesNothingAndCanBeRetriedInGroupsOfFour() throws Exception {
    try (var scope = AgentRunScope.open(owner)) {
      var job = begin();
      var pages = new ArrayList<>(read(job, 2048));
      var last = pages.getLast();
      pages.addAll(
          tools.readReviewPages(
              job.id(), "source.md", last.range().version(), last.nextOffset(), 1, 1024));
      assertThat(pages).hasSize(5);
      tools.confirmed(owner, pages);
      var before = ledger.get(job.id(), job.owner());
      var inputs = pages.stream().map(p -> assessment(p, "reviewed")).toList();
      assertThatThrownBy(() -> tools.submitReviewPages(job.id(), inputs))
          .isInstanceOf(ReviewResultRejectedException.class)
          .hasMessageContaining("1..4", "Split larger batches");
      assertThat(ledger.get(job.id(), job.owner())).isEqualTo(before);
      assertThat(tools.submitReviewPages(job.id(), inputs.subList(0, 4)))
          .hasSize(4)
          .allMatch(ReviewTools.PageSubmission::accepted);
      assertThat(tools.submitReviewPages(job.id(), inputs.subList(4, 5)))
          .hasSize(1)
          .allMatch(ReviewTools.PageSubmission::accepted);
      assertThat(ledger.get(job.id(), job.owner()).ranges())
          .allMatch(ReviewCoverageLedger.Entry::reviewed);
    }
  }

  @Test
  void batchAssessmentIsAtomicAndIdempotent() throws Exception {
    try (var scope = AgentRunScope.open(owner)) {
      var job = begin();
      var pages = read(job, 2048);
      tools.confirmed(owner, pages);
      var before = ledger.get(job.id(), job.owner());
      var inputs = new ArrayList<>(pages.stream().map(p -> assessment(p, "reviewed")).toList());
      inputs.set(1, assessment(pages.get(1), ""));
      assertThatThrownBy(() -> tools.submitReviewPages(job.id(), inputs))
          .isInstanceOf(ReviewResultRejectedException.class);
      assertThat(ledger.get(job.id(), job.owner())).isEqualTo(before);
      inputs.set(1, assessment(pages.get(1), "reviewed"));
      snapshots = 0;
      var saved = tools.submitReviewPages(job.id(), inputs);
      assertThat(snapshots).isEqualTo(1);
      assertThat(saved)
          .allMatch(p -> p.revision() == before.revision() + 1 && p.reviewedRanges() == 4);
      tools.submitReviewPages(job.id(), inputs);
      assertThat(ledger.get(job.id(), job.owner()).revision()).isEqualTo(before.revision() + 1);
      var duplicate = List.of(inputs.getFirst(), inputs.getFirst());
      assertThatThrownBy(() -> tools.submitReviewPages(job.id(), duplicate))
          .isInstanceOf(ReviewResultRejectedException.class);
    }
  }

  @Test
  void staleSourceAndForeignOwnerCannotSubmit() throws Exception {
    try (var scope = AgentRunScope.open(owner)) {
      var job = begin();
      var pages = read(job, 2048);
      tools.confirmed(owner, pages);
      var inputs = pages.stream().map(p -> assessment(p, "reviewed")).toList();
      try (var foreign =
          AgentRunScope.open(new AgentRunContext("other", "other-chat", root, "project"))) {
        assertThatThrownBy(() -> tools.submitReviewPages(job.id(), inputs))
            .isInstanceOf(dev.mikoto2000.rei.toolcontract.ToolAccessDeniedException.class);
      }
      Files.writeString(root.resolve("source.md"), "changed");
      assertThatThrownBy(() -> tools.submitReviewPages(job.id(), inputs))
          .isInstanceOf(ReviewResultRejectedException.class)
          .hasMessageContaining("restartReview");
      assertThat(ledger.get(job.id(), job.owner()).ranges())
          .noneMatch(ReviewCoverageLedger.Entry::reviewed);
    }
  }

  @Test
  void subagentEnvelopeAndBatchProgressRemainBoundedAndVisible() throws Exception {
    var child = new AgentRunContext("child", "subagent:review", root, "project");
    try (var scope = AgentRunScope.open(child)) {
      var job = begin();
      var pages = read(job, 2048);
      String wire = StorageDatabase.JSON.writeValueAsString(pages);
      String envelope =
          StorageDatabase.JSON.writeValueAsString(
              Map.of(
                  "evidenceId",
                  "0".repeat(36),
                  "tool",
                  "readReviewPages",
                  "inputSha256",
                  "0".repeat(64),
                  "outputSha256",
                  ReviewTools.hash(wire),
                  "output",
                  wire,
                  "truncated",
                  false));
      assertThat(TokenEstimator.conservative().text(envelope)).isLessThanOrEqualTo(2048);
      assertThat(ReviewTools.receipt(envelope).serializedHash())
          .isEqualTo(ReviewTools.hash(envelope));
      var progress = new dev.mikoto2000.rei.core.stagnation.ProgressEvaluator(root);
      var before = progress.beforeTool("readReviewPages", "{}");
      assertThat(progress.afterTool("readReviewPages", "{}", wire, before)).hasSize(pages.size());
      assertThat(progress.readContentObserved()).isTrue();
      tools.confirmed(child, pages);
      var submitted =
          tools.submitReviewPages(
              job.id(), pages.stream().map(p -> assessment(p, "reviewed")).toList());
      assertThat(
              progress.afterTool(
                  "submitReviewPages",
                  "{}",
                  StorageDatabase.JSON.writeValueAsString(submitted),
                  before))
          .hasSize(pages.size());
    }
  }

  @Test
  void receiptRejectsTruncationAndBindsWholeArray() throws Exception {
    try (var scope = AgentRunScope.open(owner)) {
      var pages = read(begin(), 2048);
      var wire = StorageDatabase.JSON.writeValueAsString(pages);
      assertThat(ReviewTools.receipt(wire).serializedHash()).isEqualTo(ReviewTools.hash(wire));
      var envelope =
          StorageDatabase.JSON.writeValueAsString(
              Map.of("output", wire, "outputSha256", ReviewTools.hash(wire), "truncated", true));
      assertThatThrownBy(() -> ReviewTools.receipt(envelope))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> ReviewTools.receipt(wire.substring(0, wire.length() - 1)))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }
}
