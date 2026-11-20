package dev.mikoto2000.rei.core.stagnation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.publisher.Flux;

class ProgressFeedbackTest {
  private static final String READ_ARGUMENTS = "{\"files\":[{\"path\":\"docs/intro.md\"}]}";
  @TempDir Path root;
  static final String PAGE =
      "[{\"path\":\"docs/intro.md\",\"version\":\"v1\",\"startLine\":1,\"content\":[\"known text\"],\"error\":null}]";

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void changingFeedbackPreservesSystemAndToolHistoryPrefix(boolean compressionEnabled) {
    var context = context();
    var owner =
        new dev.mikoto2000.rei.core.chat.AgentRunContext(
            "test", "conversation", root, "project");
    context.setRunContext(owner);
    var properties = new dev.mikoto2000.rei.core.contextbudget.ContextCompressionProperties();
    properties.setEnabled(compressionEnabled);
    var estimator = dev.mikoto2000.rei.core.contextbudget.TokenEstimator.conservative();
    var assembler =
        new dev.mikoto2000.rei.core.contextbudget.ContextAssembler(
            properties,
            estimator,
            new dev.mikoto2000.rei.core.contextbudget.ConversationSummaryRepository(root),
            new dev.mikoto2000.rei.core.contextbudget.ToolResultCompressor(
                new dev.mikoto2000.rei.core.contextbudget.RawToolResultStore(root),
                estimator,
                10000,
                1000),
            (previous, messages, budget, prompt) -> {
              throw new AssertionError("This short loop must not need compression");
            },
            null,
            null);
    var ledgerReads = new AtomicInteger();
    var reviews = org.mockito.Mockito.mock(dev.mikoto2000.rei.review.ReviewTools.class);
    org.mockito.Mockito.when(reviews.continuationSummary(owner))
        .thenAnswer(invocation -> "reviewedRanges=" + ledgerReads.incrementAndGet());
    assembler.setReviewTools(reviews);
    assembler.setWorkingSet(() -> "Current files: docs/intro.md");
    var original = prompt(context);
    var callbacks =
        new java.util.ArrayList<>(
            ((ToolCallingChatOptions) original.getOptions()).getToolCallbacks());
    callbacks.add(tool("beginReview", () -> "unused"));
    callbacks.add(tool("readReviewFile", () -> "unused"));
    callbacks.add(tool("submitReviewPage", () -> "unused"));
    var input =
        new Prompt(
            List.of(
                new SystemMessage("Fixed system policy"),
                new SystemMessage("Fixed project policy"),
                dev.mikoto2000.rei.core.contextbudget.ContextHistoryAdvisor.historical(
                    new UserMessage("Earlier request"), 1),
                dev.mikoto2000.rei.core.contextbudget.ContextHistoryAdvisor.historical(
                    new AssistantMessage("Earlier answer"), 2),
                new UserMessage("Review the documents"),
                SystemMessage.builder()
                    .text("Working set marker")
                    .metadata(Map.of("rei.workingSet", true))
                    .build()),
            ((ToolCallingChatOptions) original.getOptions())
                .mutate()
                .toolCallbacks(callbacks)
                .build());
    var previousPrefix = new java.util.concurrent.atomic.AtomicReference<List<Message>>(List.of());
    var calls = new AtomicInteger();
    ChatModel delegate =
        model(
            sent -> {
              int n = calls.incrementAndGet();
              var messages = sent.getInstructions();
              assertThat(messages.get(0).getText()).isEqualTo("Fixed system policy");
              assertThat(messages.get(1).getText()).isEqualTo("Fixed project policy");
              assertThat(messages.get(2).getMetadata()).containsEntry("rei.reviewGuidance", true);
              assertThat(messages.get(2).getText()).contains("beginRepositoryReview");
              assertThat(messages.getLast().getMetadata()).containsEntry("rei.workingSet", true);
              var feedback = messages.get(messages.size() - 2);
              assertThat(feedback).isInstanceOf(SystemMessage.class);
              assertThat(feedback.getMetadata()).containsEntry("rei.progressFeedback", true);
              assertThat(feedback.getText())
                  .contains("reviewedRanges=" + n)
                  .doesNotContain("For a new whole-repository review");
              if (n == 2) assertThat(feedback.getText()).contains("no-progress iterations=1/4");
              if (n == 3) assertThat(feedback.getText()).doesNotContain("Progress feedback");
              var prefix = messages.subList(0, messages.size() - 2);
              assertThat(prefix.subList(0, previousPrefix.get().size()))
                  .containsExactlyElementsOf(previousPrefix.get());
              assertThat(prefix).hasSize(6 + 2 * (n - 1));
              for (int i = 6; i < prefix.size(); i += 2) {
                var assistant = (AssistantMessage) prefix.get(i);
                var result = (ToolResponseMessage) prefix.get(i + 1);
                assertThat(result.getResponses().getFirst().id())
                    .isEqualTo(assistant.getToolCalls().getFirst().id());
              }
              previousPrefix.set(List.copyOf(prefix));
              return n == 3
                  ? new AssistantMessage("Intermediate findings saved")
                  : call(
                      n,
                      n == 1 ? "readMultiFile" : "writeTextFile",
                      n == 1 ? READ_ARGUMENTS : "{\"path\":\"review.md\"}");
            });
    new StagnationChatModel(delegate, assembler).stream(input).blockLast();
    assertThat(calls.get()).isEqualTo(3);
    assertThat(context.progressSnapshot().budget().stagnationReplans()).isEqualTo(2);
    assertThat(input.getInstructions()).hasSize(6);
    assertThat(input.getContents()).doesNotContain("Saved review ledger", "beginRepositoryReview");
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void warnsBeforeHardStopAndRecordsActualIntermediateArtifact(boolean assembled) {
    var context = context();
    var calls = new AtomicInteger();
    ChatModel delegate =
        model(
            prompt -> {
              int n = calls.incrementAndGet();
              if (n == 2) {
                assertThat(prompt.getInstructions().getFirst().getText()).isEqualTo("Fixed policy");
                assertThat(prompt.getInstructions().getLast().getMetadata())
                    .containsEntry("rei.progressFeedback", true);
                assertThat(prompt.getContents())
                    .contains("Progress feedback", "1/4", "DUPLICATE", "intermediate findings");
                assertThat(
                        prompt.getInstructions().stream()
                            .filter(
                                m ->
                                    Boolean.TRUE.equals(
                                        m.getMetadata().get("rei.progressFeedback")))
                            .count())
                    .isEqualTo(1);
              }
              if (n == 3) {
                assertThat(context.detector().stagnationCount()).isZero();
                assertThat(prompt.getContents()).doesNotContain("Progress feedback");
              }
              return n == 3
                  ? new AssistantMessage("Intermediate findings saved")
                  : call(
                      n,
                      n == 1 ? "readMultiFile" : "writeTextFile",
                      n == 1 ? READ_ARGUMENTS : "{\"path\":\"review.md\"}");
            });
    dev.mikoto2000.rei.core.contextbudget.ContextAssembler assembler = null;
    if (assembled) {
      context.setRunContext(
          new dev.mikoto2000.rei.core.chat.AgentRunContext(
              "test", "conversation", root, "project"));
      assembler =
          org.mockito.Mockito.mock(dev.mikoto2000.rei.core.contextbudget.ContextAssembler.class);
      org.mockito.Mockito.when(
              assembler.assemble(
                  org.mockito.ArgumentMatchers.any(),
                  org.mockito.ArgumentMatchers.anyString(),
                  org.mockito.ArgumentMatchers.anyString(),
                  org.mockito.ArgumentMatchers.any()))
          .thenAnswer(invocation -> invocation.getArgument(0));
    }
    new StagnationChatModel(delegate, assembler).stream(prompt(context)).blockLast();
    assertThat(calls.get()).isEqualTo(3);
    assertThat(root.resolve("review.md")).hasContent("Concrete intermediate findings");
    assertThat(context.detector().stagnationCount()).isEqualTo(1);
    assertThat(context.progressSnapshot().budget().stagnationReplans()).isEqualTo(2);
  }

  @Test
  void ignoringFeedbackStillStopsAtOriginalThreshold() {
    var context = context();
    var calls = new AtomicInteger();
    ChatModel delegate =
        model(
            prompt -> {
              int n = calls.incrementAndGet();
              if (n > 1)
                assertThat(prompt.getContents())
                    .contains("Progress feedback", (n - 1) + "/4", "DUPLICATE");
              return call(n, "readMultiFile", READ_ARGUMENTS);
            });
    assertThatThrownBy(() -> new StagnationChatModel(delegate).stream(prompt(context)).blockLast())
        .isInstanceOf(ExecutionStoppedException.class)
        .hasMessageContaining("REPLAN_BUDGET_EXCEEDED");
    assertThat(calls.get()).isEqualTo(4);
    assertThat(context.detector().stagnationCount()).isEqualTo(4);
    assertThat(context.progressSnapshot().budget().stagnationReplans()).isEqualTo(2);
  }

  @Test
  void restoredStalledStateReceivesFeedbackBeforeFirstModelInvocation() throws Exception {
    var context = context();
    try (var input =
        getClass().getResourceAsStream("/checkpoint/skill-read-stalled-progress.json")) {
      context.restoreProgress(
          new com.fasterxml.jackson.databind.ObjectMapper()
              .readValue(input, RunExecutionContext.ProgressState.class));
    }
    ChatModel delegate =
        model(
            prompt -> {
              assertThat(prompt.getContents())
                  .contains(
                      "Progress feedback",
                      "5/4",
                      "restorationReads=8",
                      "replansUsed=2",
                      "coverage is not proof");
              throw new java.util.concurrent.CancellationException();
            });
    assertThatThrownBy(() -> new StagnationChatModel(delegate).stream(prompt(context)).blockLast())
        .isInstanceOf(java.util.concurrent.CancellationException.class);
    assertThat(context.progressSnapshot().budget().stagnationReplans()).isEqualTo(2);
  }

  @Test
  void freshInformationClearsWarningWithoutRefundingSharedReplans() {
    var context = context();
    var calls = new AtomicInteger();
    var reads = new AtomicInteger();
    var callback =
        tool("readMultiFile", () -> reads.incrementAndGet() == 1 ? PAGE : PAGE.replace("v1", "v2"));
    var input =
        new Prompt(
            "Review the documents",
            ToolCallingChatOptions.builder()
                .toolCallbacks(callback)
                .toolContext(Map.of(RunExecutionContext.KEY, context))
                .build());
    ChatModel delegate =
        model(
            prompt -> {
              int n = calls.incrementAndGet();
              if (n == 2) assertThat(prompt.getContents()).contains("Progress feedback");
              if (n == 3) assertThat(prompt.getContents()).doesNotContain("Progress feedback");
              return n == 3
                  ? new AssistantMessage("partial report")
                  : call(n, "readMultiFile", READ_ARGUMENTS);
            });
    new StagnationChatModel(delegate).stream(input).blockLast();
    assertThat(context.progressSnapshot().budget().stagnationReplans()).isEqualTo(2);
  }

  private RunExecutionContext context() {
    var budget = new OutputLimitRunBudget(2, 20);
    budget.tryConsumeStagnationReplan();
    budget.tryConsumeStagnationReplan();
    budget.tryConsumeLlmCall();
    var evaluator = new ProgressEvaluator(root);
    evaluator.afterTool("readMultiFile", READ_ARGUMENTS, PAGE, evaluator.beforeTool("readMultiFile", READ_ARGUMENTS));
    return new RunExecutionContext(
        "test", budget, evaluator, new AgentEventFactory(Clock.systemUTC()), e -> {});
  }

  private Prompt prompt(RunExecutionContext c) {
    var read = tool("readMultiFile", () -> PAGE);
    var write =
        tool(
            "writeTextFile",
            () -> {
              try {
                Files.writeString(root.resolve("review.md"), "Concrete intermediate findings");
              } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
              }
              return "{\"success\":true}";
            });
    return new Prompt(
        List.of(new SystemMessage("Fixed policy"), new UserMessage("Review the documents")),
        ToolCallingChatOptions.builder()
            .toolCallbacks(read, write)
            .toolContext(Map.of(RunExecutionContext.KEY, c))
            .build());
  }

  private ToolCallback tool(String name, java.util.function.Supplier<String> action) {
    return new ToolCallback() {
      public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder().name(name).description(name).inputSchema("{}").build();
      }

      public String call(String input) {
        return action.get();
      }

      public String call(String input, ToolContext context) {
        return call(input);
      }
    };
  }

  private ChatModel model(java.util.function.Function<Prompt, AssistantMessage> action) {
    return new ChatModel() {
      public ChatResponse call(Prompt p) {
        throw new UnsupportedOperationException();
      }

      public Flux<ChatResponse> stream(Prompt p) {
        return Flux.defer(
            () -> Flux.just(new ChatResponse(List.of(new Generation(action.apply(p))))));
      }
    };
  }

  private AssistantMessage call(int id, String tool, String input) {
    return AssistantMessage.builder()
        .content("")
        .toolCalls(List.of(new AssistantMessage.ToolCall("c" + id, "function", tool, input)))
        .build();
  }
}
