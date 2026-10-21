package dev.mikoto2000.rei.core.contextbudget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

/** 無効状態圧縮安全性の結果と状態の契約を検証するテスト。 */
class DisabledCompressionSafetyTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path dir;

  /** 無効状態圧縮引き続きについて、大きすぎる最終入力を受け付けないことを確認する。 */
  @Test
  void disabledCompressionStillRejectsOversizedFinalInput() {
    var config = new ContextCompressionProperties();
    config.setEnabled(false);
    config.setHardLimit(100);
    var estimator = TokenEstimator.conservative();
    var assembler =
        new ContextAssembler(
            config,
            estimator,
            new ConversationSummaryRepository(dir),
            new ToolResultCompressor(new RawToolResultStore(dir), estimator, 8000, 2000),
            (previous, messages, budget, prompt) -> {
              throw new AssertionError("Compression disabled");
            },
            null,
            null);
    assertThatThrownBy(
            () ->
                assembler.assemble(
                    new Prompt(List.of(new UserMessage("日".repeat(100)))), "chat", "run", () -> {}))
        .hasMessageContaining("CONTEXT_HARD_LIMIT");
  }

  /** モデルへ渡す文脈の管理で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void delegatedResultsAreLosslessEvenWhenConversationCompressionIsDisabled() throws Exception {
    var config = new ContextCompressionProperties();
    config.setEnabled(false);
    config.setHardLimit(1000);
    var estimator = TokenEstimator.conservative();
    var store = new RawToolResultStore(dir);
    var assembler =
        new ContextAssembler(
            config,
            estimator,
            new ConversationSummaryRepository(dir),
            new ToolResultCompressor(store, estimator, 100, 70),
            (previous, messages, budget, prompt) -> {
              throw new AssertionError("Compression disabled");
            },
            null,
            null);
    String raw = "{\"findings\":[\"" + "教材🙂".repeat(5000) + "\"]}";
    var tool =
        org.springframework.ai.chat.messages.ToolResponseMessage.builder()
            .responses(
                List.of(
                    new org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse(
                        "call", "delegateTask", raw)))
            .metadata(
                java.util.Map.of(
                    "rei.deliveryPolicies",
                    java.util.Map.of("call", ResultDelivery.Kind.LOSSLESS_RESULT)))
            .build();
    var prompt = new Prompt(List.of(new UserMessage("review"), tool));
    var projected = assembler.assemble(prompt, "chat", "run", () -> {});
    var delivered =
        (org.springframework.ai.chat.messages.ToolResponseMessage)
            projected.getInstructions().getLast();
    var page =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(delivered.getResponses().getFirst().responseData());
    assertThat(page.path("content").asText())
        .isEqualTo(raw.substring(0, page.path("nextOffset").asInt()));
    assertThat(store.read("chat", page.path("rawResultRef").asText()).rawResult()).isEqualTo(raw);
    assertThat(ContextBudgetManager.estimateMessages(projected.getInstructions(), estimator))
        .isLessThanOrEqualTo(1000);
    assertThat(tool.getResponses().getFirst().responseData()).isEqualTo(raw);
  }
}
