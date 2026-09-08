package dev.mikoto2000.rei.core.stagnation;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.*;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import dev.mikoto2000.rei.event.*;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;
import reactor.core.publisher.Flux;

class StagnationChatModelTest {
  @Test
  void modelFailureStillEndsTheIteration() {
    var context = context(3, new ArrayList<>());
    ChatModel failing = new ChatModel() {
      public ChatResponse call(Prompt p) { throw new UnsupportedOperationException(); }
      public Flux<ChatResponse> stream(Prompt p) { return Flux.error(new IllegalStateException("network failed")); }
    };
    assertThatThrownBy(() -> new StagnationChatModel(failing).stream(prompt(context, () -> "unused")).blockLast())
        .hasMessageContaining("network failed");
    assertThat(context.detector().stagnationCount()).isEqualTo(1);
  }

  @Test
  void cancelledRunCannotExecuteAnotherToolOrLlmCall() {
    var context = context(5, new ArrayList<>());
    context.close();
    var calls = new AtomicInteger();
    assertThatThrownBy(() -> model(new ArrayList<>(), calls, false).stream(prompt(context, () -> "unused")).blockLast())
        .isInstanceOf(java.util.concurrent.CancellationException.class);
    assertThat(calls.get()).isZero();
  }
  @Test
  void outputLimitsWithVerifiedProgressContinueWithoutSpendingReplans() {
    var calls = new AtomicInteger();
    var context = context(10, new ArrayList<>());
    ChatModel delegate = new ChatModel() {
      public ChatResponse call(Prompt p) { throw new UnsupportedOperationException(); }
      public Flux<ChatResponse> stream(Prompt p) {
        int n = calls.incrementAndGet();
        if (n > 7) return Flux.error(new IllegalStateException("unexpected call"));
        AssistantMessage message = n % 2 == 1 && n < 7
            ? AssistantMessage.builder().content("").toolCalls(List.of(
                new AssistantMessage.ToolCall("id" + n, "function", "readFile", "{}"))).build()
            : new AssistantMessage(n == 7 ? "done" : "partial");
        String reason = n == 7 ? "stop" : n % 2 == 0 ? "length" : "tool_calls";
        return Flux.just(new ChatResponse(List.of(new Generation(message,
            org.springframework.ai.chat.metadata.ChatGenerationMetadata.builder().finishReason(reason).build()))));
      }
    };
    var output = new StagnationChatModel(delegate).stream(prompt(context, () -> "content" + calls.get())).collectList().block();
    assertThat(output.getLast().getResult().getOutput().getText()).isEqualTo("done");
    assertThat(calls.get()).isEqualTo(7);
    assertThat(context.detector().replanCount()).isZero();
  }

  @Test
  void batchOfFourDuplicateToolsIsOneIteration() {
    var calls = new AtomicInteger();
    var context = context(3, new ArrayList<>());
    ChatModel delegate = new ChatModel() {
      public ChatResponse call(Prompt p) { throw new UnsupportedOperationException(); }
      public Flux<ChatResponse> stream(Prompt p) {
        int n = calls.incrementAndGet();
        var batch = java.util.stream.IntStream.range(0, 4).mapToObj(i ->
            new AssistantMessage.ToolCall(n + "-" + i, "function", "readFile", "{}")).toList();
        return Flux.just(new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("").toolCalls(batch).build()))));
      }
    };
    assertThatThrownBy(() -> new StagnationChatModel(delegate).stream(prompt(context, () -> "same")).blockLast())
        .hasMessageContaining("LLM_CALL_BUDGET_EXCEEDED");
    assertThat(context.detector().stagnationCount()).isEqualTo(2);
  }
  @Test
  void repeatedSuccessfulReadStopsAfterTwoUnsuccessfulReplans() {
    var calls = new AtomicInteger();
    List<Prompt> prompts = new ArrayList<>();
    List<AgentEvent> events = new ArrayList<>();
    var context = context(30, events);
    var model = model(prompts, calls, false);
    assertThatThrownBy(() -> model.stream(prompt(context, () -> "same contents")).blockLast())
        .isInstanceOf(ExecutionStoppedException.class)
        .hasMessageContaining("STAGNATED");
    assertThat(calls.get()).isEqualTo(13); // first read is new, then 3 x 4 without progress
    assertThat(prompts.stream().filter(p -> p.getContents().contains("Replan Notice")).count()).isGreaterThanOrEqualTo(2);
    assertThat(events.stream().filter(e -> e.type() == AgentEventType.STAGNATION_REPLAN_REQUESTED).count()).isEqualTo(2);
    assertThat(events.getLast().type()).isEqualTo(AgentEventType.STAGNATION_STOPPED);
  }

  @Test
  void newInformationKeepsRunningUntilHardLlmBudget() {
    var calls = new AtomicInteger();
    var results = new AtomicInteger();
    List<AgentEvent> events = new ArrayList<>();
    var context = context(6, events);
    assertThatThrownBy(() -> model(new ArrayList<>(), calls, false)
        .stream(prompt(context, () -> "new content " + results.incrementAndGet())).blockLast())
        .isInstanceOf(ExecutionStoppedException.class).hasMessageContaining("LLM_CALL_BUDGET_EXCEEDED");
    assertThat(calls.get()).isEqualTo(6);
    assertThat(events).noneMatch(e -> e.type() == AgentEventType.STAGNATION_REPLAN_REQUESTED);
  }

  @Test
  void recoveryAfterReplanAllowsNormalCompletion() {
    var calls = new AtomicInteger();
    List<AgentEvent> events = new ArrayList<>();
    var context = context(30, events);
    var response = model(new ArrayList<>(), calls, true)
        .stream(prompt(context, () -> calls.get() < 6 ? "same" : "new" )).collectList().block();
    assertThat(response.getLast().getResult().getOutput().getText()).isEqualTo("done");
    assertThat(events).anyMatch(e -> e.type() == AgentEventType.STAGNATION_RECOVERED);
    assertThat(context.detector().replanCount()).isZero();
  }

  private RunExecutionContext context(int limit, List<AgentEvent> events) {
    var budget = new OutputLimitRunBudget(2, limit);
    budget.tryConsumeLlmCall(); // top-level prompt is reserved by ChatExecutionService
    return new RunExecutionContext("test-run", budget, new ProgressEvaluator(Path.of(".")),
        new AgentEventFactory(Clock.systemUTC()), events::add);
  }

  private Prompt prompt(RunExecutionContext context, java.util.function.Supplier<String> result) {
    ToolCallback callback = new ToolCallback() {
      public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder().name("readFile").description("read").inputSchema("{}").build();
      }
      public String call(String input) { return result.get(); }
      public String call(String input, ToolContext toolContext) { return call(input); }
    };
    return new Prompt("work", ToolCallingChatOptions.builder().toolCallbacks(callback)
        .toolContext(Map.of(RunExecutionContext.KEY, context)).build());
  }

  private StagnationChatModel model(List<Prompt> prompts, AtomicInteger calls, boolean finish) {
    ChatModel delegate = new ChatModel() {
      public ChatResponse call(Prompt prompt) { throw new UnsupportedOperationException(); }
      public Flux<ChatResponse> stream(Prompt prompt) {
        assertThat(((ToolCallingChatOptions) prompt.getOptions()).getInternalToolExecutionEnabled()).isFalse();
        prompts.add(prompt);
        int n = calls.incrementAndGet();
        AssistantMessage message = finish && n == 7 ? new AssistantMessage("done")
            : AssistantMessage.builder().content("").toolCalls(List.of(
                new AssistantMessage.ToolCall("call-" + n, "function", "readFile", "{\"path\":\"A\"}"))).build();
        return Flux.just(new ChatResponse(List.of(new Generation(message))));
      }
    };
    return new StagnationChatModel(delegate);
  }
}
