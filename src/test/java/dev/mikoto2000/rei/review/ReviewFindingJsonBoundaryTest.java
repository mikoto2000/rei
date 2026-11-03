package dev.mikoto2000.rei.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import dev.mikoto2000.rei.event.ToolEventCallbackDecorator;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;
import dev.mikoto2000.rei.storage.StorageDatabase;
import dev.mikoto2000.rei.storage.StorageMigrationCoordinator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;

/** Exercise the actual tool JSON decoder, delivery confirmation and correction loop. */
class ReviewFindingJsonBoundaryTest {
  @TempDir Path directory;

  @ParameterizedTest
  @CsvSource({"true,omitted", "true,null", "true,null-start", "true,null-end",
      "false,omitted", "false,null", "false,null-start", "false,null-end"})
  void nullableFindingPositionsLocateLegacyQuotesAndRequireDurableCoordinates(
      boolean legacy, String shape) throws Exception {
    Path root = Files.createDirectory(directory.resolve("repo"));
    String text = "本文🙂\nUnique evidence.\n";
    String quote = "Unique evidence.";
    Files.writeString(root.resolve("lesson.md"), text);
    Path data = directory.resolve("data");
    try (var migration = new StorageMigrationCoordinator(data)) { migration.prepare(); }
    var ledger = new ReviewCoverageLedger(new StorageDatabase(data));
    var raw = new RawToolResultStore(data);
    var estimator = TokenEstimator.conservative();
    var reviews = new ReviewTools(ledger, raw, new Tools(), estimator);
    var owner = new AgentRunContext("run", "session", root, "project");
    try (var active = AgentRunScope.open(owner)) {
      String task;
      if (legacy) task = "legacy:" + reviews.beginReview(List.of("lesson.md"), "correctness", "v1").id();
      else {
        task = reviews.beginRepositoryReview("WORKTREE", List.of(), 100, 1048576L).id();
        reviews.scanReviewSnapshot(task, 100);
      }
      var assembler = new ContextAssembler(new ContextCompressionProperties(), estimator,
          new ConversationSummaryRepository(data), new ToolResultCompressor(raw, estimator, 8000, 2000),
          (old, messages, budget, prompt) -> "saved state", null, null);
      assembler.setReviewTools(reviews);
      var events = new AgentEventFactory(Clock.systemUTC());
      var execution = new RunExecutionContext("run", new OutputLimitRunBudget(2, 10),
          new ProgressEvaluator(root), events, event -> {});
      execution.setRunContext(owner);
      var callbacks = Arrays.stream(DeliveryToolRegistration.provider(reviews).getToolCallbacks())
          .<org.springframework.ai.tool.ToolCallback>map(t -> new ToolEventCallbackDecorator(t, events, event -> {})).toList();
      var options = OpenAiChatOptions.builder().toolCallbacks(callbacks)
          .toolContext(Map.of(RunExecutionContext.KEY, execution)).build();
      var model = mock(ChatModel.class);
      var calls = new AtomicInteger();
      var delivered = new ReviewTools.Page[1];
      when(model.stream(any(Prompt.class))).thenAnswer(invocation -> {
        int n = calls.incrementAndGet();
        String name;
        Map<String, Object> arguments;
        if (n == 1) {
          name = "readNextReviewWorkItem";
          arguments = Map.of("taskId", task);
        } else if (n == 2 || !legacy && n == 3) {
          var prompt = (Prompt) invocation.getArgument(0);
          if (n == 2) {
            var response = (ToolResponseMessage) prompt.getInstructions().getLast();
            delivered[0] = StorageDatabase.JSON.readValue(response.getResponses().getFirst().responseData(), ReviewTools.Page.class);
          } else {
            assertThat(prompt.getInstructions().toString()).contains("REVIEW_RESULT_INVALID").doesNotContain("Cannot map");
            assertThat(ledger.durableTasks().status(
                new dev.mikoto2000.rei.durable.DurableTaskStore.Scope("project", "session", root.toString()), task).state()).isEqualTo("ACTIVE");
          }
          var finding = new LinkedHashMap<String, Object>();
          finding.put("id", "F1");
          finding.put("severity", "P2");
          finding.put("evidence", quote);
          finding.put("assessment", "candidate verified against source");
          if (n == 3 || !shape.equals("omitted")) {
            finding.put("start", n == 3 || shape.equals("null-end") ? text.indexOf(quote) : null);
            finding.put("end", n == 3 || shape.equals("null-start") ? text.indexOf(quote) + quote.length() : null);
          }
          name = "submitReviewWorkItem";
          arguments = Map.of("workItemId", delivered[0].workItemId(), "attempt", delivered[0].attempt(),
              "assessment", "reviewed complete source unit", "findings", List.of(finding));
        } else {
          var prompt = (Prompt) invocation.getArgument(0);
          var response = (ToolResponseMessage) prompt.getInstructions().getLast();
          var result = StorageDatabase.JSON.readTree(response.getResponses().getFirst().responseData());
          assertThat(result.path("body").path("findings").get(0).path("start").asInt()).isEqualTo(text.indexOf(quote));
          assertThat(result.path("body").path("findings").get(0).path("end").asInt()).isEqualTo(text.indexOf(quote) + quote.length());
          if (legacy) assertThat(result.path("legacyReceipt").path("accepted").asBoolean()).isTrue();
          return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("Review complete.")))));
        }
        return Flux.just(new ChatResponse(List.of(new Generation(AssistantMessage.builder()
            .toolCalls(List.of(new AssistantMessage.ToolCall("call-" + n, "function", name,
                StorageDatabase.JSON.writeValueAsString(arguments)))).build()))));
      });
      new StagnationChatModel(model, assembler).stream(new Prompt(new UserMessage("review"), options)).blockLast();
      assertThat(calls).hasValue(legacy ? 3 : 4);
      assertThat(execution.progressSnapshot().budget().argumentCorrections()).isEqualTo(legacy ? 0 : 1);
      reviews.requireComplete(owner);
    }
  }
}
