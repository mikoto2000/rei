package dev.mikoto2000.rei.core.stagnation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;
import java.net.SocketTimeoutException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.publisher.Flux;

class StreamTimeoutRetryTest {
  @TempDir Path root;

  private RunExecutionContext context(OutputLimitRunBudget budget) {
    var context =
        new RunExecutionContext(
            "run",
            budget,
            new ProgressEvaluator(root),
            new AgentEventFactory(Clock.systemUTC()),
            event -> {});
    context.setRunContext(new AgentRunContext("run", "session", root, "project"));
    return context;
  }

  private static ChatResponse text(String text) {
    return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void retriesSamePromptAndDiscardsIncompleteAggregate(boolean partial) {
    var model = mock(ChatModel.class);
    var calls = new AtomicInteger();
    var prompts = new ArrayList<Prompt>();
    when(model.stream(any(Prompt.class)))
        .thenAnswer(
            invocation -> {
              prompts.add(invocation.getArgument(0));
              if (calls.incrementAndGet() == 1) {
                var failure =
                    Flux.<ChatResponse>error(new SocketTimeoutException("read timed out"));
                return partial ? Flux.concat(Flux.just(text("discard")), failure) : failure;
              }
              return Flux.just(text("success"));
            });
    var budget = new OutputLimitRunBudget(2, 5);
    var context = context(budget);
    context.consumeNextLlmCall();
    var completed = new ArrayList<ChatResponse>();
    var prompt = new Prompt("work");
    var chunks =
        StreamTimeoutRetry.stream(model, prompt, context, 2, completed::add).collectList().block();
    assertThat(prompts).containsExactly(prompt, prompt);
    assertThat(completed).hasSize(1);
    assertThat(completed.getFirst().getResult().getOutput().getText()).isEqualTo("success");
    assertThat(chunks.stream().filter(c -> StreamTimeoutRetry.retryAttempt(c) > 0).count())
        .isEqualTo(1);
    assertThat(budget.snapshot().llmCalls()).isEqualTo(2);
  }

  @Test
  void exhaustsBoundedRetriesAndDoesNotRetryOtherErrors() {
    var model = mock(ChatModel.class);
    var calls = new AtomicInteger();
    when(model.stream(any(Prompt.class)))
        .thenAnswer(
            invocation -> {
              calls.incrementAndGet();
              return Flux.error(new SocketTimeoutException());
            });
    assertThatThrownBy(
            () ->
                StreamTimeoutRetry.stream(
                        model,
                        new Prompt("work"),
                        context(new OutputLimitRunBudget(2, 10)),
                        2,
                        response -> {})
                    .blockLast())
        .hasRootCauseInstanceOf(SocketTimeoutException.class);
    assertThat(calls.get()).isEqualTo(3);
    assertThat(
            StreamTimeoutRetry.isReadTimeout(
                new IllegalStateException(new SocketTimeoutException())))
        .isFalse();
    assertThat(StreamTimeoutRetry.isReadTimeout(new java.util.concurrent.TimeoutException()))
        .isFalse();
  }

  @Test
  void unknownTokenUsageAndCallCeilingPreventRetry() {
    var model = mock(ChatModel.class);
    var calls = new AtomicInteger();
    when(model.stream(any(Prompt.class)))
        .thenAnswer(
            invocation -> {
              calls.incrementAndGet();
              return Flux.error(new SocketTimeoutException());
            });
    var tokenContext = context(new OutputLimitRunBudget(2, 10, null, 100));
    assertThatThrownBy(
            () ->
                StreamTimeoutRetry.stream(
                        model, new Prompt("work"), tokenContext, 2, response -> {})
                    .blockLast())
        .isInstanceOf(ExecutionStoppedException.class);
    assertThat(calls.get()).isEqualTo(1);
    var callContext = context(new OutputLimitRunBudget(2, 1));
    callContext.consumeNextLlmCall();
    assertThatThrownBy(
            () ->
                StreamTimeoutRetry.stream(model, new Prompt("work"), callContext, 2, response -> {})
                    .blockLast())
        .isInstanceOf(ExecutionStoppedException.class);
    assertThat(calls.get()).isEqualTo(2);
  }

  @Test
  void cancellationDuringBackoffDoesNotSendAgain() {
    var model = mock(ChatModel.class);
    var context = context(new OutputLimitRunBudget(2, 5));
    var calls = new AtomicInteger();
    when(model.stream(any(Prompt.class)))
        .thenAnswer(
            invocation -> {
              calls.incrementAndGet();
              return Flux.error(new SocketTimeoutException());
            });
    var subscription =
        StreamTimeoutRetry.stream(model, new Prompt("work"), context, 2, response -> {})
            .subscribe(response -> {}, error -> {});
    context.cancel();
    subscription.dispose();
    assertThat(calls.get()).isEqualTo(1);
  }

  @Test
  void legacyMemoryDoesNotSaveFailedAttemptText() {
    var model = mock(ChatModel.class);
    when(model.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
    var calls = new AtomicInteger();
    when(model.stream(any(Prompt.class)))
        .thenAnswer(
            invocation ->
                calls.incrementAndGet() == 1
                    ? Flux.concat(
                        Flux.just(text("discard")), Flux.error(new SocketTimeoutException()))
                    : Flux.just(text("success")));
    var context = context(new OutputLimitRunBudget(2, 10));
    var memory = org.springframework.ai.chat.memory.MessageWindowChatMemory.builder().build();
    var advisor =
        org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor.builder(memory).build();
    var client =
        org.springframework.ai.chat.client.ChatClient.builder(
                new StagnationChatModel(model, null, 2))
            .defaultAdvisors(new dev.mikoto2000.rei.core.chat.RunScopedAdvisor(advisor))
            .build();
    var options =
        ToolCallingChatOptions.builder()
            .toolContext(Map.of(RunExecutionContext.KEY, context))
            .build();
    try (var scope = dev.mikoto2000.rei.core.chat.AgentRunScope.open(context.runContext())) {
      client
          .prompt(new Prompt("work", options))
          .advisors(
              a ->
                  a.param(org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID, "session"))
          .stream()
          .chatResponse()
          .blockLast();
    }
    assertThat(memory.get("project:project:session").toString())
        .contains("success")
        .doesNotContain("discard");
  }

  @Test
  void partialToolCallIsNotExecutedAndPreviousToolResultIsNotReplayed() {
    var model = mock(ChatModel.class);
    var calls = new AtomicInteger();
    var toolCalls = new AtomicInteger();
    var tool =
        new org.springframework.ai.tool.ToolCallback() {
          public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
            return org.springframework.ai.tool.definition.ToolDefinition.builder()
                .name("write")
                .description("write once")
                .inputSchema("{\"type\":\"object\"}")
                .build();
          }

          public String call(String arguments) {
            toolCalls.incrementAndGet();
            return "saved";
          }
        };
    var context = context(new OutputLimitRunBudget(2, 10));
    var options =
        ToolCallingChatOptions.builder()
            .toolCallbacks(tool)
            .toolContext(Map.of(RunExecutionContext.KEY, context))
            .build();
    when(model.stream(any(Prompt.class)))
        .thenAnswer(
            invocation -> {
              int n = calls.incrementAndGet();
              var call =
                  new ChatResponse(
                      List.of(
                          new Generation(
                              AssistantMessage.builder()
                                  .content("")
                                  .toolCalls(
                                      List.of(
                                          new AssistantMessage.ToolCall(
                                              "write-" + n, "function", "write", "{}")))
                                  .build())));
              if (n == 1) return Flux.just(call);
              if (n == 2)
                return Flux.concat(Flux.just(call), Flux.error(new SocketTimeoutException()));
              assertThat(((Prompt) invocation.getArgument(0)).getInstructions().toString())
                  .contains("saved");
              return Flux.just(text("done"));
            });
    var chunks =
        new StagnationChatModel(model, null, 2)
            .stream(new Prompt("work", options)).collectList().block();
    assertThat(toolCalls.get()).isEqualTo(1);
    assertThat(calls.get()).isEqualTo(3);
    assertThat(chunks.getLast().getResult().getOutput().getText()).isEqualTo("done");
  }
}
