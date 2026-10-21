package dev.mikoto2000.rei.core.contextbudget;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.BoundedToolLoop;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.annotation.Tool;
import reactor.core.publisher.Flux;

/** 上限内に収めた配信の結果と状態の契約を検証するテスト。 */
class BoundedDeliveryTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path dir;

  /** 検証条件を再現するための読み取り元。 */
  static class Reader {
    /** 検証用に、必要な内容を参照できるよう、検証条件を読み取る。 */
    @Tool(description = "read")
    @ResultDelivery(ResultDelivery.Kind.FILE_CONTENT)
    String readFixture() {
      return "教材🙂".repeat(2000);
    }
  }

  /** 独立した繰り返しについて、同一最終入力利用条件の確認を利用することを確認する。 */
  @Test
  void isolatedLoopUsesSameFinalInputGuardAndRetainsStepLimit() {
    var estimator = TokenEstimator.conservative();
    var props = new ContextCompressionProperties();
    props.setHardLimit(1000);
    var assembler =
        new ContextAssembler(
            props,
            estimator,
            new ConversationSummaryRepository(dir),
            new ToolResultCompressor(new RawToolResultStore(dir), estimator, 8000, 2000),
            (old, messages, budget, prompt) -> "summary",
            null,
            null);
    var calls = new AtomicInteger();
    ChatModel model =
        new ChatModel() {
          /** 検証用に、モデルへ渡す文脈の管理の要求を実行し、呼び出し元へ結果を返す。 */
          public ChatResponse call(Prompt prompt) {
            throw new UnsupportedOperationException();
          }

          /** {@code stream} の条件を再現するための補助情報を返す。 */
          public Flux<ChatResponse> stream(Prompt prompt) {
            assertThat(ContextBudgetManager.estimateMessages(prompt.getInstructions(), estimator))
                .isLessThanOrEqualTo(1000);
            if (calls.incrementAndGet() == 1)
              return Flux.just(
                  new ChatResponse(
                      List.of(
                          new Generation(
                              AssistantMessage.builder()
                                  .toolCalls(
                                      List.of(
                                          new AssistantMessage.ToolCall(
                                              "read", "function", "readFixture", "{}")))
                                  .build()))));
            return Flux.just(
                new ChatResponse(List.of(new Generation(new AssistantMessage("done")))));
          }
        };
    var callbacks = DeliveryToolRegistration.provider(new Reader()).getToolCallbacks();
    var options = OpenAiChatOptions.builder().toolCallbacks(callbacks).build();
    var remaining = new AtomicInteger(2);
    var outcome =
        new BoundedToolLoop(assembler)
            .runWithHistory(
                model,
                new Prompt(new UserMessage("inspect"), options),
                remaining,
                new AgentRunContext("child", "subagent:child", dir),
                () -> {})
            .block();
    assertThat(outcome.output()).isEqualTo("done");
    assertThat(calls).hasValue(2);
    assertThat(remaining).hasValue(0);
  }
}
