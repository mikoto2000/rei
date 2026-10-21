package dev.mikoto2000.rei.core.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.calls;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.core.service.ModelHolderService;
import dev.mikoto2000.rei.core.stagnation.StagnationChatModel;
import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventType;
import dev.mikoto2000.rei.event.AgentRunCompletedPayload;
import dev.mikoto2000.rei.event.AgentRunFailedPayload;
import dev.mikoto2000.rei.llm.FixedLlmChatClientProvider;
import dev.mikoto2000.rei.llm.FixedLlmModelProvider;
import dev.mikoto2000.rei.llm.LlmProperties;
import dev.mikoto2000.rei.llm.OutputLimitReplanner;
import dev.mikoto2000.rei.llm.RunAwareToolCallingAdvisor;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.publisher.Flux;

/** チャット実行停滞の結果と状態の契約を検証するテスト。 */
class ChatExecutionStagnationTest {
  /** 会話の実行の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void cancellationAtOutputLimitDoesNotStartPlanner() {
    ChatModel model =
        new ChatModel() {
          /** 検証用に、会話の実行で参照する選択可能な設定を返す。 */
          @Override
          public ToolCallingChatOptions getOptions() {
            return ToolCallingChatOptions.builder().build();
          }

          /** 検証用に、会話の実行の要求を実行し、呼び出し元へ結果を返す。 */
          public ChatResponse call(Prompt p) {
            throw new UnsupportedOperationException();
          }

          /** {@code stream} の条件を再現するための補助情報を返す。 */
          public Flux<ChatResponse> stream(Prompt p) {
            var options = (org.springframework.ai.model.tool.ToolCallingChatOptions) p.getOptions();
            ((dev.mikoto2000.rei.core.stagnation.RunExecutionContext)
                    options
                        .getToolContext()
                        .get(dev.mikoto2000.rei.core.stagnation.RunExecutionContext.KEY))
                .cancel();
            return Flux.just(
                new ChatResponse(
                    List.of(
                        new Generation(
                            new AssistantMessage("partial"),
                            org.springframework.ai.chat.metadata.ChatGenerationMetadata.builder()
                                .finishReason("length")
                                .build()))));
          }
        };
    var holder = mock(ModelHolderService.class);
    when(holder.get()).thenReturn("test");
    var planner = mock(OutputLimitReplanner.class);
    var service =
        new ChatExecutionService(
            new FixedLlmChatClientProvider(ChatClient.builder(model).build()),
            holder,
            new FixedLlmModelProvider(),
            new LlmProperties(),
            new CommandCancellationService(),
            Optional.empty(),
            Optional.of(planner));
    assertThat(service.execute("work").status()).isEqualTo(ChatExecutionResult.Status.CANCELLED);
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(planner);
  }

  /** 会話の実行で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
  void recoveredOutputLimitIsNotReportedAsTerminalFailure(boolean callBudgetEnabled) {
    AtomicInteger calls = new AtomicInteger();
    ChatModel model =
        new ChatModel() {
          /** 検証用に、会話の実行で参照する選択可能な設定を返す。 */
          @Override
          public ToolCallingChatOptions getOptions() {
            return ToolCallingChatOptions.builder().build();
          }

          /** 検証用に、会話の実行の要求を実行し、呼び出し元へ結果を返す。 */
          public ChatResponse call(Prompt p) {
            throw new UnsupportedOperationException();
          }

          /** {@code stream} の条件を再現するための補助情報を返す。 */
          public Flux<ChatResponse> stream(Prompt p) {
            int n = calls.incrementAndGet();
            AssistantMessage output =
                n == 1
                    ? AssistantMessage.builder()
                        .content("")
                        .toolCalls(
                            List.of(
                                new AssistantMessage.ToolCall(
                                    "read", "function", "readFile", "{}")))
                        .build()
                    : new AssistantMessage(n == 2 ? "partial" : "done");
            String reason = n == 1 ? "tool_calls" : n == 2 ? "length" : "stop";
            return Flux.just(
                new ChatResponse(
                    List.of(
                        new Generation(
                            output,
                            org.springframework.ai.chat.metadata.ChatGenerationMetadata.builder()
                                .finishReason(reason)
                                .build())),
                    org.springframework.ai.chat.metadata.ChatResponseMetadata.builder()
                        .usage(new org.springframework.ai.chat.metadata.DefaultUsage(0, n))
                        .build()));
          }
        };
    ToolCallback read =
        new ToolCallback() {
          /** 検証用に、会話の実行で参照するツール定義を返す。 */
          public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                .name("readFile")
                .description("read")
                .inputSchema("{}")
                .build();
          }

          /** 検証用に、会話の実行の要求を実行し、呼び出し元へ結果を返す。 */
          public String call(String input) {
            return "new information";
          }
        };
    ChatClient client =
        ChatClient.builder(new StagnationChatModel(model))
            .defaultAdvisors(new RunAwareToolCallingAdvisor())
            .defaultTools(read)
            .build();
    List<AgentEvent> events = new ArrayList<>();
    var holder = mock(ModelHolderService.class);
    when(holder.get()).thenReturn("test");
    var planner = mock(OutputLimitReplanner.class);
    var properties = new LlmProperties();
    properties.getOutputLimit().setLlmCallBudgetEnabled(callBudgetEnabled);
    properties.getOutputLimit().setMaxLlmCallsPerRun(callBudgetEnabled ? 3 : 1);
    var service =
        new ChatExecutionService(
            new FixedLlmChatClientProvider(client),
            holder,
            new FixedLlmModelProvider(),
            properties,
            new CommandCancellationService(),
            Optional.empty(),
            Optional.of(planner),
            Optional.empty(),
            Optional.empty(),
            Clock.systemUTC(),
            Optional.empty(),
            new AgentEventFactory(Clock.systemUTC()),
            events::add);
    var result = service.execute("work");
    assertThat(result.success()).isTrue();
    assertThat(result.text()).endsWith("done");
    assertThat(calls.get()).isEqualTo(3);
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(planner);
    assertThat(events).noneMatch(e -> e.type() == AgentEventType.AGENT_RUN_FAILED);
    var completed =
        events.stream()
            .filter(e -> e.type() == AgentEventType.AGENT_RUN_COMPLETED)
            .findFirst()
            .orElseThrow();
    assertThat(((AgentRunCompletedPayload) completed.payload()).completionTokens()).isEqualTo(6L);
  }

  /** 会話の実行で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
  void actualChatClientStopsAndPublishesTypedFailureAndResetsNextRun(boolean callBudgetEnabled) {
    AtomicInteger calls = new AtomicInteger();
    ChatModel model =
        new ChatModel() {
          /** 検証用に、会話の実行で参照する選択可能な設定を返す。 */
          @Override
          public ToolCallingChatOptions getOptions() {
            return ToolCallingChatOptions.builder().build();
          }

          /** 検証用に、会話の実行の要求を実行し、呼び出し元へ結果を返す。 */
          public ChatResponse call(Prompt p) {
            throw new UnsupportedOperationException();
          }

          /** {@code stream} の条件を再現するための補助情報を返す。 */
          public Flux<ChatResponse> stream(Prompt p) {
            int n = calls.incrementAndGet();
            if (n > 35) return Flux.error(new IllegalStateException("loop guard"));
            return Flux.just(
                new ChatResponse(
                    List.of(
                        new Generation(
                            AssistantMessage.builder()
                                .content("")
                                .toolCalls(
                                    List.of(
                                        new AssistantMessage.ToolCall(
                                            "call-" + n, "function", "readFile", "{}")))
                                .build()))));
          }
        };
    ToolCallback read =
        new ToolCallback() {
          /** 検証用に、会話の実行で参照するツール定義を返す。 */
          public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                .name("readFile")
                .description("read")
                .inputSchema("{}")
                .build();
          }

          /** 検証用に、会話の実行の要求を実行し、呼び出し元へ結果を返す。 */
          public String call(String input) {
            return "unchanged";
          }
        };
    ChatClient client =
        ChatClient.builder(new StagnationChatModel(model))
            .defaultAdvisors(new RunAwareToolCallingAdvisor())
            .defaultTools(read)
            .build();
    List<AgentEvent> events = new ArrayList<>();
    var holder = mock(ModelHolderService.class);
    when(holder.get()).thenReturn("test");
    var properties = new LlmProperties();
    properties.getOutputLimit().setLlmCallBudgetEnabled(callBudgetEnabled);
    properties.getOutputLimit().setMaxLlmCallsPerRun(callBudgetEnabled ? 120 : 1);
    var service =
        new ChatExecutionService(
            new FixedLlmChatClientProvider(client),
            holder,
            new FixedLlmModelProvider(),
            properties,
            new CommandCancellationService(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Clock.systemUTC(),
            Optional.empty(),
            new AgentEventFactory(Clock.systemUTC()),
            events::add);
    var first = service.execute("work");
    assertThat(first.success()).isFalse();
    assertThat(first.errorMessage()).contains("STAGNATED");
    assertThat(calls.get()).isEqualTo(13);
    var failed =
        events.stream()
            .filter(e -> e.type() == AgentEventType.AGENT_RUN_FAILED)
            .findFirst()
            .orElseThrow();
    assertThat(((AgentRunFailedPayload) failed.payload()).error().code()).isEqualTo("stagnated");
    service.execute("new run");
    assertThat(calls.get()).isEqualTo(26);
  }
}
