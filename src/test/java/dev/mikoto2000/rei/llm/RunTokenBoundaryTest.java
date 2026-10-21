package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.calls;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.BoundedToolLoop;
import dev.mikoto2000.rei.core.chat.ChatExecutionService;
import dev.mikoto2000.rei.core.stagnation.ProgressEvaluator;
import dev.mikoto2000.rei.core.stagnation.RunExecutionContext;
import dev.mikoto2000.rei.core.stagnation.StagnationChatModel;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.InMemoryAgentEventBus;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.publisher.Flux;

/** 実行トークン境界条件の結果と状態の契約を検証するテスト。 */
class RunTokenBoundaryTest {
  /** 検証用に、用途に応じたモデル利用の操作を実行する。 */
  RunExecutionContext run(OutputLimitRunBudget budget) {
    return new RunExecutionContext(
        "run",
        budget,
        new ProgressEvaluator(Path.of("."), null),
        new AgentEventFactory(Clock.systemUTC()),
        new InMemoryAgentEventBus());
  }

  /** 検証で使用する応答を、指定された条件に合わせて用意する。 */
  ChatResponse response(int tokens, List<AssistantMessage.ToolCall> calls) {
    return new ChatResponse(
        List.of(
            new Generation(AssistantMessage.builder().content("answer").toolCalls(calls).build())),
        ChatResponseMetadata.builder().usage(new DefaultUsage(1, tokens - 1)).build());
  }

  /** 用途に応じたモデル利用で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void overLimitParentResponseCannotExecuteRequestedTool() {
    var count = new AtomicInteger();
    ToolCallback tool =
        new ToolCallback() {
          /** 検証用に、用途に応じたモデル利用で参照するツール定義を返す。 */
          public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                .name("readMultiFile")
                .description("read")
                .inputSchema("{}")
                .build();
          }

          /** 検証用に、用途に応じたモデル利用の要求を実行し、呼び出し元へ結果を返す。 */
          public String call(String input) {
            count.incrementAndGet();
            return "read";
          }
        };
    var model = mock(ChatModel.class);
    when(model.stream(any(Prompt.class)))
        .thenReturn(
            Flux.just(
                response(
                    11,
                    List.of(
                        new AssistantMessage.ToolCall(
                            "call", "function", "readMultiFile", "{}")))));
    var context = run(new OutputLimitRunBudget(0, 10, null, 10));
    context.consumeNextLlmCall();
    var prompt =
        new Prompt(
            "read",
            ToolCallingChatOptions.builder()
                .toolCallbacks(tool)
                .toolContext(Map.of(RunExecutionContext.KEY, context))
                .build());
    assertThatThrownBy(() -> new StagnationChatModel(model).stream(prompt).collectList().block())
        .hasMessageContaining("TOKEN_BUDGET_EXCEEDED");
    assertThat(count).hasValue(0);
  }

  /** 用途に応じたモデル利用で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void unknownFailedChildUsageBlocksParentAndReportedChildUsageIsCharged() {
    for (boolean failed : List.of(false, true)) {
      var budget = new OutputLimitRunBudget(0, 10, null, 10);
      var context = run(budget);
      var model = mock(ChatModel.class);
      when(model.stream(any(Prompt.class)))
          .thenReturn(
              failed
                  ? Flux.error(new IllegalStateException("private"))
                  : Flux.just(response(7, List.of())));
      var loop = new BoundedToolLoop();
      var prompt = new Prompt("read", ToolCallingChatOptions.builder().build());
      var output =
          loop.runWithHistory(
              model,
              prompt,
              new AtomicInteger(2),
              new AgentRunContext("child", "subagent:child", Path.of(".")),
              () -> {},
              context.sharedLlmReservation());
      if (failed) {
        assertThatThrownBy(output::block).hasMessageContaining("TOKEN_USAGE_UNKNOWN");
        assertThat(context.sharedLlmReservation().tryReserve()).isFalse();
      } else {
        assertThat(output.block().output()).isEqualTo("answer");
        assertThat(budget.totalTokens()).isEqualTo(7);
      }
    }
  }

  /** 用途に応じたモデル利用で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void prepaidCallCannotStartAfterHelperExhaustsTokens() {
    var model = mock(ChatModel.class);
    var context = run(new OutputLimitRunBudget(0, 10, null, 5));
    context.consumeNextLlmCall();
    context.recordTotalTokens(5);
    var prompt =
        new Prompt(
            "read",
            ToolCallingChatOptions.builder()
                .toolContext(Map.of(RunExecutionContext.KEY, context))
                .build());
    assertThatThrownBy(() -> new StagnationChatModel(model).stream(prompt).collectList().block())
        .hasMessageContaining("TOKEN_BUDGET_EXCEEDED");
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(model);
  }

  /** 実際のチャット境界条件について、トークン停止を伝えることを確認する。 */
  @Test
  void actualChatBoundaryReportsTokenStopInsteadOfSuccess() {
    var model = mock(ChatModel.class);
    when(model.stream(any(Prompt.class))).thenReturn(Flux.just(response(11, List.of())));
    when(model.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
    var holder = mock(dev.mikoto2000.rei.core.service.ModelHolderService.class);
    when(holder.get()).thenReturn("test");
    var properties = new LlmProperties();
    properties.getOutputLimit().setMaxTotalTokensPerRun(10);
    var client =
        org.springframework.ai.chat.client.ChatClient.builder(new StagnationChatModel(model))
            .defaultAdvisors(new RunAwareToolCallingAdvisor())
            .build();
    var service =
        new ChatExecutionService(
            new FixedLlmChatClientProvider(client),
            holder,
            new FixedLlmModelProvider(),
            properties,
            new dev.mikoto2000.rei.core.service.CommandCancellationService(),
            Optional.empty(),
            Optional.empty());
    var result = service.execute("answer");
    assertThat(result.success()).isFalse();
    assertThat(result.errorMessage()).contains("token");
  }
}
