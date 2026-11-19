package dev.mikoto2000.rei.review;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mikoto2000.rei.core.Tools;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.AgentRunScope;
import dev.mikoto2000.rei.core.chat.BoundedToolLoop;
import dev.mikoto2000.rei.core.contextbudget.DeliveryToolRegistration;
import dev.mikoto2000.rei.core.contextbudget.RawToolResultStore;
import dev.mikoto2000.rei.core.contextbudget.RawToolResultTools;
import dev.mikoto2000.rei.core.contextbudget.TokenEstimator;
import dev.mikoto2000.rei.core.stagnation.ProgressEvaluator;
import dev.mikoto2000.rei.core.stagnation.RunExecutionContext;
import dev.mikoto2000.rei.core.stagnation.StagnationChatModel;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.ToolEventCallbackProvider;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;
import dev.mikoto2000.rei.storage.StorageDatabase;
import dev.mikoto2000.rei.storage.StorageMigrationCoordinator;
import dev.mikoto2000.rei.temporal.SchedulerTools;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import reactor.core.publisher.Flux;

@org.junit.jupiter.api.Tag("integration")
class ToolRecoveryRegressionTest {
  enum Rejection {
    ENUMERATION_NOT_SEALED,
    TASK_ACCESS_DENIED,
    SOURCE_NOT_DELIVERED,
    RESULT_CONFLICT,
    STALE_VERSION,
    INVALID_CURSOR,
    INVALID_SCHEDULE_TIMESTAMP,
    INVALID_RAW_RESULT_REF,
    RAW_RESULT_NOT_FOUND,
    INVALID_TOOL_ARGUMENTS
  }

  @TempDir Path temporary;
  private ReviewTools reviews;
  private AgentRunContext owner;
  private List<ToolCallback> callbacks;
  private AgentEventFactory events;
  private final AtomicInteger siblingCalls = new AtomicInteger();

  @BeforeEach
  void setUp() throws Exception {
    Path root = Files.createDirectory(temporary.resolve("repo"));
    Files.writeString(root.resolve("lesson.txt"), "Frozen source.");
    Path data = temporary.resolve("data");
    try (var migration = new StorageMigrationCoordinator(data)) {
      migration.prepare();
    }
    var raw = new RawToolResultStore(data);
    reviews =
        new ReviewTools(
            new ReviewCoverageLedger(new StorageDatabase(data)),
            raw,
            new Tools(),
            TokenEstimator.conservative());
    owner = new AgentRunContext("run", "session", root, "project");
    events = new AgentEventFactory(Clock.systemUTC());
    callbacks =
        List.of(
            new ToolEventCallbackProvider(
                    DeliveryToolRegistration.provider(
                        reviews,
                        new RawToolResultTools(raw),
                        new SchedulerTools(null),
                        new Sibling()),
                    events,
                    event -> {})
                .getToolCallbacks());
  }

  public final class Sibling {
    @Tool(description = "Count successful calls for the regression test.")
    public int sibling(String marker) {
      return siblingCalls.incrementAndGet();
    }
  }

  @ParameterizedTest
  @EnumSource(Rejection.class)
  void rejectionReturnsAlongsideSuccessfulCallsBeforeAndAfterIt(Rejection code) throws Exception {
    AssistantMessage.ToolCall rejected;
    try (var active = AgentRunScope.open(owner)) {
      rejected = rejection(code);
    }
    var execution = execution();
    var calls = new AtomicInteger();
    var model =
        model(
            prompt -> {
              if (calls.incrementAndGet() == 1)
                return batch(sibling("before"), rejected, sibling("after"));
              var response = (ToolResponseMessage) prompt.getInstructions().getLast();
              assertThat(response.getResponses())
                  .extracting(ToolResponseMessage.ToolResponse::id)
                  .containsExactly("before", "rejected", "after");
              assertThat(response.getResponses().getFirst().responseData()).isEqualTo("1");
              assertThat(response.getResponses().getLast().responseData()).isEqualTo("2");
              assertThat(response.getResponses().get(1).responseData())
                  .contains("\"error\":\"" + code + "\"", "\"guidance\":");
              assertThat(response.getMetadata().get("rei.reviewReceipts")).isNull();
              return new AssistantMessage("Continued.");
            });
    var output = new StagnationChatModel(model).call(prompt(execution));
    assertThat(output.getResult().getOutput().getText()).isEqualTo("Continued.");
    assertThat(siblingCalls).hasValue(2);
    assertThat(calls).hasValue(2);
    assertThat(execution.progressSnapshot().budget().argumentCorrections())
        .isEqualTo(code == Rejection.TASK_ACCESS_DENIED ? 0 : 1);
    if (code == Rejection.RESULT_CONFLICT) {
      try (var active = AgentRunScope.open(owner)) {
        String task = reviews.listReviewTasks(null, null).entries().getFirst().id();
        assertThat(reviews.listReviewTaskResults(task, null, null).entries())
            .singleElement()
            .satisfies(saved -> assertThat(saved.body().assessment()).isEqualTo("Original"));
      }
    }
  }

  @Test
  void allOptionalToolUsesTheSameCorrectionWhenCalledAlone() throws Exception {
    var rejected = rejection(Rejection.INVALID_TOOL_ARGUMENTS);
    var calls = new AtomicInteger();
    var execution = execution();
    var model =
        model(
            prompt ->
                switch (calls.incrementAndGet()) {
                  case 1 -> batch(rejected);
                  case 2 -> {
                    var response = (ToolResponseMessage) prompt.getInstructions().getLast();
                    assertThat(response.getResponses().getFirst().responseData())
                        .contains("INVALID_TOOL_ARGUMENTS");
                    yield batch(call("fixed", "listReviewTasks", Map.of()));
                  }
                  case 3 -> {
                    var response = (ToolResponseMessage) prompt.getInstructions().getLast();
                    assertThat(response.getResponses().getFirst().responseData())
                        .contains("\"entries\":[]");
                    yield new AssistantMessage("Continued.");
                  }
                  default -> throw new AssertionError("Unexpected model call");
                });
    new StagnationChatModel(model).call(prompt(execution));
    assertThat(calls).hasValue(3);
    assertThat(execution.progressSnapshot().budget().argumentCorrections()).isEqualTo(1);
  }

  @Test
  void duplicateIdsCanBeReissuedWithoutReplayingSuccessfulBatches() {
    var calls = new AtomicInteger();
    var execution = execution();
    var model =
        model(
            prompt ->
                switch (calls.incrementAndGet()) {
                  case 1 -> batch(sibling("saved"));
                  case 2 -> batch(sibling("duplicate"), sibling("duplicate"));
                  case 3 -> {
                    assertThat(prompt.getInstructions().getLast().getText())
                        .contains("duplicate tool-call IDs");
                    assertThat(prompt.getInstructions())
                        .anySatisfy(
                            message -> {
                              assertThat(message).isInstanceOf(ToolResponseMessage.class);
                              assertThat(
                                      ((ToolResponseMessage) message)
                                          .getResponses()
                                          .getFirst()
                                          .id())
                                  .isEqualTo("saved");
                            });
                    assertThat(siblingCalls).hasValue(1);
                    yield batch(sibling("fixed"));
                  }
                  case 4 -> new AssistantMessage("Continued.");
                  default -> throw new AssertionError("Unexpected model call");
                });
    new StagnationChatModel(model).call(prompt(execution));
    assertThat(siblingCalls).hasValue(2);
    assertThat(calls).hasValue(4);
    assertThat(execution.progressSnapshot().budget().argumentCorrections()).isEqualTo(1);
  }

  @Test
  void helperModelCanCorrectUnavailableToolWithinItsStepBudget() {
    var calls = new AtomicInteger();
    var model =
        model(
            prompt ->
                switch (calls.incrementAndGet()) {
                  case 1 -> batch(call("typo", "siblnig", Map.of()));
                  case 2 -> {
                    assertThat(prompt.getInstructions().getLast().getText())
                        .contains("unavailable tool");
                    yield batch(sibling("corrected"));
                  }
                  case 3 -> new AssistantMessage("Continued.");
                  default -> throw new AssertionError("Unexpected model call");
                });
    var options = OpenAiChatOptions.builder().toolCallbacks(callbacks).build();
    assertThat(
            new BoundedToolLoop()
                .run(
                    model,
                    new Prompt(new UserMessage("Verify recovery."), options),
                    3,
                    owner,
                    () -> {})
                .block())
        .isEqualTo("Continued.");
    assertThat(siblingCalls).hasValue(1);
  }

  private static AssistantMessage.ToolCall sibling(String id) {
    return call(id, "sibling", Map.of("marker", id));
  }

  private AssistantMessage.ToolCall rejection(Rejection code) throws Exception {
    return switch (code) {
      case ENUMERATION_NOT_SEALED -> {
        String task = reviews.beginRepositoryReview("WORKTREE", List.of(), 10, 1048576L).id();
        yield call("rejected", "readNextReviewWorkItem", Map.of("taskId", task));
      }
      case TASK_ACCESS_DENIED -> call("rejected", "reviewTaskStatus", Map.of("taskId", "unknown"));
      case SOURCE_NOT_DELIVERED, RESULT_CONFLICT -> {
        String task = reviews.beginRepositoryReview("WORKTREE", List.of(), 10, 1048576L).id();
        reviews.scanReviewSnapshot(task, 100);
        var page = reviews.readNextReviewWorkItem(task, null);
        if (code == Rejection.RESULT_CONFLICT) {
          reviews.confirmed(owner, List.of(page));
          reviews.submitReviewWorkItem(page.workItemId(), page.attempt(), "Original", List.of());
        }
        yield call(
            "rejected",
            "submitReviewWorkItem",
            Map.of(
                "workItemId",
                page.workItemId(),
                "attempt",
                page.attempt(),
                "assessment",
                "New",
                "findings",
                List.of()));
      }
      case STALE_VERSION, INVALID_CURSOR -> {
        var job = reviews.beginReview(List.of("lesson.txt"), "correctness", "v1");
        yield call(
            "rejected",
            "readReviewFile",
            Map.of(
                "reviewJobId",
                job.id(),
                "path",
                "lesson.txt",
                "tokenBudget",
                512,
                "expectedVersion",
                code == Rejection.STALE_VERSION ? "old" : job.targets().getFirst().version(),
                "offset",
                code == Rejection.INVALID_CURSOR ? -1 : 0));
      }
      case INVALID_SCHEDULE_TIMESTAMP ->
          call(
              "rejected",
              "scheduleAt",
              Map.of(
                  "timestamp", "tomorrow", "action", "Read report", "conversationId", "session"));
      case INVALID_RAW_RESULT_REF, RAW_RESULT_NOT_FOUND ->
          call(
              "rejected",
              "readRawToolResult",
              Map.of(
                  "rawResultRef",
                  code == Rejection.INVALID_RAW_RESULT_REF
                      ? "invalid"
                      : UUID.randomUUID().toString(),
                  "offset",
                  0,
                  "limit",
                  1));
      case INVALID_TOOL_ARGUMENTS ->
          call("rejected", "listReviewTasks", Map.of("limit", Map.of("not", "an integer")));
    };
  }

  private RunExecutionContext execution() {
    var execution =
        new RunExecutionContext(
            owner.runId(),
            new OutputLimitRunBudget(2, 10),
            new ProgressEvaluator(owner.projectRoot()),
            events,
            event -> {});
    execution.setRunContext(owner);
    return execution;
  }

  private Prompt prompt(RunExecutionContext execution) {
    var options =
        OpenAiChatOptions.builder()
            .toolCallbacks(callbacks)
            .toolContext(Map.of(RunExecutionContext.KEY, execution))
            .build();
    return new Prompt(new UserMessage("Verify recovery."), options);
  }

  private static AssistantMessage.ToolCall call(String id, String name, Map<String, ?> arguments) {
    return new AssistantMessage.ToolCall(
        id,
        "function",
        name,
        tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(arguments));
  }

  private static AssistantMessage batch(AssistantMessage.ToolCall... calls) {
    return AssistantMessage.builder().toolCalls(List.of(calls)).build();
  }

  private static ChatModel model(Function<Prompt, AssistantMessage> reply) {
    return new ChatModel() {
      @Override
      public ChatResponse call(Prompt prompt) {
        throw new UnsupportedOperationException();
      }

      @Override
      public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.just(new ChatResponse(List.of(new Generation(reply.apply(prompt)))));
      }
    };
  }
}
