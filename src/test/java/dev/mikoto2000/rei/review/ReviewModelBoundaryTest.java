package dev.mikoto2000.rei.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.calls;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.core.Tools;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.AgentRunScope;
import dev.mikoto2000.rei.core.contextbudget.ContextAssembler;
import dev.mikoto2000.rei.core.contextbudget.ContextCompressionProperties;
import dev.mikoto2000.rei.core.contextbudget.ConversationSummaryRepository;
import dev.mikoto2000.rei.core.contextbudget.DeliveryToolRegistration;
import dev.mikoto2000.rei.core.contextbudget.RawToolResultStore;
import dev.mikoto2000.rei.core.contextbudget.TokenEstimator;
import dev.mikoto2000.rei.core.contextbudget.ToolResultCompressor;
import dev.mikoto2000.rei.core.stagnation.ProgressEvaluator;
import dev.mikoto2000.rei.core.stagnation.RunExecutionContext;
import dev.mikoto2000.rei.core.stagnation.StagnationChatModel;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;
import dev.mikoto2000.rei.storage.StorageDatabase;
import dev.mikoto2000.rei.storage.StorageMigrationCoordinator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;

/** レビューモデル境界条件の結果と状態の契約を検証するテスト。 */
class ReviewModelBoundaryTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path dir;

  /** 変更内容のレビューで、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @ParameterizedTest
  @CsvSource({"true,false", "false,false", "true,true", "false,true"})
  void failedTransportNeverConfirmsDeliveryAndNaturalCompletionNeverConfirmsReview(
      boolean transportFailure, boolean durable) throws Exception {
    var repository = Files.createDirectory(dir.resolve("repository"));
    Files.writeString(repository.resolve("lesson.md"), "本文🙂");
    try (var migration = new StorageMigrationCoordinator(dir.resolve("data"))) {
      migration.prepare();
    }
    var ledger = new ReviewCoverageLedger(new StorageDatabase(dir.resolve("data")));
    var raw = new RawToolResultStore(dir.resolve("data"));
    var estimator = TokenEstimator.conservative();
    var reviews = new ReviewTools(ledger, raw, new Tools(), estimator);
    var owner = new AgentRunContext("run", "chat", repository, "project");
    ReviewCoverageLedger.Job job = null;
    dev.mikoto2000.rei.durable.WorkItemStore.Claim claim = null;
    try (var scope = AgentRunScope.open(owner)) {
      if (durable) {
        var task = reviews.beginRepositoryReview("WORKTREE", List.of(), 100, 1048576L);
        reviews.scanReviewSnapshot(task.id(), 100);
        claim = reviews.claimReviewWorkItem(task.id(), 1024);
      } else job = reviews.beginReview(List.of("lesson.md"), "correctness", "v1");
    }
    var assembler =
        new ContextAssembler(
            new ContextCompressionProperties(),
            estimator,
            new ConversationSummaryRepository(dir.resolve("data")),
            new ToolResultCompressor(raw, estimator, 8000, 2000),
            (old, messages, budget, prompt) -> "stored review state",
            null,
            null);
    assembler.setReviewTools(reviews);
    var execution =
        new RunExecutionContext(
            "run",
            new OutputLimitRunBudget(2, 10),
            new ProgressEvaluator(repository),
            new AgentEventFactory(Clock.systemUTC()),
            event -> {});
    execution.setRunContext(owner);
    var callbacks = DeliveryToolRegistration.provider(reviews).getToolCallbacks();
    var options =
        OpenAiChatOptions.builder()
            .toolCallbacks(callbacks)
            .toolContext(Map.of(RunExecutionContext.KEY, execution))
            .build();
    var model = mock(ChatModel.class);
    var calls = new AtomicInteger();
    String args =
        StorageDatabase.JSON.writeValueAsString(
            durable ? Map.of("workItemId", claim.item().id(), "attempt", claim.attempt()) : Map.of(
                "reviewJobId",
                job.id(),
                "path",
                "lesson.md",
                "expectedVersion",
                job.targets().getFirst().version(),
                "offset",
                0,
                "tokenBudget",
                256));
    when(model.stream(any(Prompt.class)))
        .thenAnswer(
            invocation -> {
              if (calls.incrementAndGet() == 1)
                return Flux.just(
                    new ChatResponse(
                        List.of(
                            new Generation(
                                AssistantMessage.builder()
                                    .toolCalls(
                                        List.of(
                                            new AssistantMessage.ToolCall(
                                                "read", "function", durable ? "readReviewWorkItem" : "readReviewFile", args)))
                                    .build()))));
              return transportFailure
                  ? Flux.error(new IllegalStateException("transport"))
                  : Flux.just(
                      new ChatResponse(List.of(new Generation(new AssistantMessage("レビュー完了")))));
            });
    assertThatThrownBy(
            () ->
                new StagnationChatModel(model, assembler)
                    .stream(new Prompt(new UserMessage("review"), options)).blockLast())
        .hasMessageContaining(transportFailure ? "transport" : "REVIEW_PARTIAL");
    if (durable) {
      var scope = new dev.mikoto2000.rei.durable.DurableTaskStore.Scope("project", "chat", repository.toString());
      var work = new dev.mikoto2000.rei.durable.WorkItemStore(ledger.durableTasks());
      assertThat(work.deliveredHash(scope, claim).isEmpty()).isEqualTo(transportFailure);
      assertThat(ledger.durableTasks().status(scope, claim.item().task()).state()).isEqualTo("ACTIVE");
    } else {
      var saved = ledger.get(job.id(), ReviewTools.scope(owner));
      assertThat(saved.ranges().getFirst().delivered()).isEqualTo(!transportFailure);
      assertThat(saved.ranges().getFirst().reviewed()).isFalse();
      assertThat(saved.status()).isNotEqualTo("COMPLETE");
    }
  }
}
