package dev.mikoto2000.rei.core.contextbudget;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

/** Adaptive配信予算の結果と状態の契約を検証するテスト。 */
class AdaptiveDeliveryBudgetTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path dir;

  /** モデルへ渡す文脈の管理で、指定した条件に対する結果と状態の変化を確認する。 */
  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void filePageShrinksToFinalInputRemainderEvenWhenConversationCompressionIsDisabled(
      boolean enabled) throws Exception {
    var properties = new ContextCompressionProperties();
    properties.setEnabled(enabled);
    properties.setHardLimit(600);
    var estimator = TokenEstimator.conservative();
    var assembler =
        new ContextAssembler(
            properties,
            estimator,
            new ConversationSummaryRepository(dir),
            new ToolResultCompressor(new RawToolResultStore(dir), estimator, 8000, 2000),
            (previous, messages, budget, prompt) -> {
              throw new AssertionError("No prefix needs summarizing");
            },
            null,
            null);
    String source = "教材🙂\\\"\n".repeat(1000);
    var tool =
        ToolResponseMessage.builder()
            .responses(List.of(new ToolResponseMessage.ToolResponse("call", "reader", source)))
            .metadata(
                Map.of("rei.deliveryPolicies", Map.of("call", ResultDelivery.Kind.FILE_CONTENT)))
            .build();
    var prompt =
        new Prompt(List.of(new UserMessage("x".repeat(800)), new AssistantMessage("read"), tool));
    var result = assembler.assemble(prompt, "chat", "run", () -> {});
    assertThat(ContextBudgetManager.estimateMessages(result.getInstructions(), estimator))
        .isLessThanOrEqualTo(600);
    var delivered = (ToolResponseMessage) result.getInstructions().getLast();
    var page =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(delivered.getResponses().getFirst().responseData());
    assertThat(page.path("content").asText())
        .isEqualTo(source.substring(0, page.path("nextOffset").asInt()));
    assertThat(page.path("nextOffset").asInt()).isPositive();
  }
}
