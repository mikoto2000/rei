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
  @TempDir Path root;
  static final String PAGE =
      "[{\"path\":\"docs/intro.md\",\"version\":\"v1\",\"startLine\":1,\"content\":[\"known text\"],\"error\":null}]";

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
                      n == 1 ? "{}" : "{\"path\":\"review.md\"}");
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
              return call(n, "readMultiFile", "{}");
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
                  : call(n, "readMultiFile", "{}");
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
    evaluator.afterTool("readMultiFile", "{}", PAGE, evaluator.beforeTool("readMultiFile", "{}"));
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
        "Review the documents",
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
