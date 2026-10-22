package dev.mikoto2000.rei.core.contextbudget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.calls;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.stagnation.ProgressEvaluator;
import dev.mikoto2000.rei.core.stagnation.RunExecutionContext;
import dev.mikoto2000.rei.core.stagnation.StagnationChatModel;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.publisher.Flux;

/** 文脈境界条件の結果と状態の契約を検証するテスト。 */
class ContextBoundaryTest {
  /** 加工前のプロンプト範囲外表示用の状態を維持することを確認する。 */
  @Test
  void assemblesAfterToolsCompleteAndKeepsRawPromptOutsideProjection() {
    List<String> order = new ArrayList<>();
    var events = new AgentEventFactory(Clock.systemUTC());
    var context =
        new RunExecutionContext(
            "run", new OutputLimitRunBudget(2, 10), mock(ProgressEvaluator.class), events, e -> {});
    context.setRunContext(new AgentRunContext("run", "chat", Path.of(".")));
    when(context.evaluator().afterTool(any(), any(), any(), any())).thenReturn(List.of());
    when(context.evaluator().readFeedback()).thenReturn("[]");
    when(context.evaluator().snapshot()).thenReturn(new ProgressEvaluator(Path.of(".")).snapshot());
    var assembler = mock(ContextAssembler.class);
    when(assembler.assemble(any(), any(), any(), any()))
        .thenAnswer(
            invocation -> {
              order.add("assemble");
              Prompt raw = invocation.getArgument(0);
              if (raw.getInstructions().getLast() instanceof ToolResponseMessage tool) {
                assertThat(tool.getResponses().getFirst().responseData())
                    .isEqualTo("full raw result");
                return new Prompt(
                    List.of(new UserMessage("compact representation")), raw.getOptions());
              }
              return raw;
            });
    var tool =
        new ToolCallback() {
          /** 検証用に、モデルへ渡す文脈の管理で参照するツール定義を返す。 */
          public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                .name("readFile")
                .description("read")
                .inputSchema("{}")
                .build();
          }

          /** 検証用に、モデルへ渡す文脈の管理の要求を実行し、呼び出し元へ結果を返す。 */
          public String call(String input) {
            order.add("tool completed");
            return "full raw result";
          }
        };
    ChatModel model =
        new ChatModel() {
          /** 検証に使う呼び出し回数。 */
          int calls;

          /** 検証用に、モデルへ渡す文脈の管理の要求を実行し、呼び出し元へ結果を返す。 */
          public ChatResponse call(Prompt prompt) {
            throw new UnsupportedOperationException();
          }

          /** {@code stream} の条件を再現するための補助情報を返す。 */
          public Flux<ChatResponse> stream(Prompt prompt) {
            order.add("llm");
            if (++calls == 1)
              return Flux.just(
                  new ChatResponse(
                      List.of(
                          new Generation(
                              AssistantMessage.builder()
                                  .content("")
                                  .toolCalls(
                                      List.of(
                                          new AssistantMessage.ToolCall(
                                              "id", "function", "readFile", "{}")))
                                  .build()))));
            assertThat(prompt.getContents()).contains("compact representation");
            return Flux.just(
                new ChatResponse(List.of(new Generation(new AssistantMessage("done")))));
          }
        };
    var options =
        ToolCallingChatOptions.builder()
            .toolCallbacks(tool)
            .toolContext(Map.of(RunExecutionContext.KEY, context))
            .build();
    new StagnationChatModel(model, assembler)
        .stream(new Prompt(new UserMessage("request"), options)).blockLast();
    assertThat(order).containsExactly("assemble", "llm", "tool completed", "assemble", "llm");
  }
}
