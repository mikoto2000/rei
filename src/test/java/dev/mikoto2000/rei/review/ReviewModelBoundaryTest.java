package dev.mikoto2000.rei.review;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.mikoto2000.rei.core.*;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.core.contextbudget.*;
import dev.mikoto2000.rei.core.stagnation.*;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;
import dev.mikoto2000.rei.storage.*;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;

class ReviewModelBoundaryTest {
  @TempDir Path dir;

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void failedTransportNeverConfirmsDeliveryAndNaturalCompletionNeverConfirmsReview(
      boolean transportFailure) throws Exception {
    Files.writeString(dir.resolve("lesson.md"), "本文🙂");
    try (var migration = new StorageMigrationCoordinator(dir.resolve("data"))) {
      migration.prepare();
    }
    var ledger = new ReviewCoverageLedger(new StorageDatabase(dir.resolve("data")));
    var raw = new RawToolResultStore(dir.resolve("data"));
    var estimator = TokenEstimator.conservative();
    var reviews = new ReviewTools(ledger, raw, new Tools(), estimator);
    var owner = new AgentRunContext("run", "chat", dir, "project");
    ReviewCoverageLedger.Job job;
    try (var scope = AgentRunScope.open(owner)) {
      job = reviews.beginReview(List.of("lesson.md"), "correctness", "v1");
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
            new ProgressEvaluator(dir),
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
            Map.of(
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
                                                "read", "function", "readReviewFile", args)))
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
    var saved = ledger.get(job.id(), ReviewTools.scope(owner));
    assertThat(saved.ranges().getFirst().delivered()).isEqualTo(!transportFailure);
    assertThat(saved.ranges().getFirst().reviewed()).isFalse();
    assertThat(saved.status()).isNotEqualTo("COMPLETE");
  }
}
