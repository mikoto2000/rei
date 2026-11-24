package dev.mikoto2000.rei.core.contextbudget;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

/** 圧縮判断基準の結果と状態の契約を検証するテスト。 */
class CompressionPolicyTest {
  /** 検証に使う概算。 */
  final TokenEstimator estimator = TokenEstimator.conservative();

  /** モデルへ渡す文脈の管理で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void countsJapaneseAndToolArgumentsRatherThanOnlyMessageText() {
    assertThat(estimator.text("日本語")).isGreaterThanOrEqualTo(3);
    var call =
        AssistantMessage.builder()
            .content("")
            .toolCalls(
                List.of(
                    new AssistantMessage.ToolCall("id", "function", "readFile", "x".repeat(400))))
            .build();
    assertThat(estimator.message(call)).isGreaterThanOrEqualTo(100);
  }

  /** 直近予算を保持することを確認する。 */
  @Test
  void retainsRecentBudgetAndNeverSplitsToolPairs() {
    var call =
        AssistantMessage.builder()
            .content("")
            .toolCalls(List.of(new AssistantMessage.ToolCall("id", "function", "readFile", "{}")))
            .build();
    var result =
        ToolResponseMessage.builder()
            .responses(
                List.of(new ToolResponseMessage.ToolResponse("id", "readFile", "x".repeat(200))))
            .build();
    List<Message> messages = List.of(new UserMessage("old".repeat(300)), call, result);
    assertThat(new CompressionPolicy(estimator).prefixToCompress(messages, 40)).isZero();
  }

  /** モデルへ渡す文脈の管理で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void protectedSectionsGetBudgetBeforeOptionalOnes() {
    var budget = new ContextBudgetManager(100, 10, 10);
    var result =
        budget.allocate(
            List.of(
                new ContextSection("TOOL_RESULTS", "x".repeat(240)),
                new ContextSection("SYSTEM", "x".repeat(200)),
                new ContextSection("CURRENT_USER", "x".repeat(40))));
    assertThat(result.included()).containsExactly("SYSTEM", "CURRENT_USER");
    assertThat(result.dropped()).containsExactly("TOOL_RESULTS");
  }
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"missing", "orphan", "duplicate", "name", "null-name-orphan"})
  void incompleteOrInconsistentToolTransactionsCannotBeCompressed(String defect) {
    var call = AssistantMessage.builder().content("").toolCalls(List.of(
        new AssistantMessage.ToolCall("one", "function", "readFile", "{}"),
        new AssistantMessage.ToolCall("two", "function", "readFile", "{}"))).build();
    var results = new java.util.ArrayList<ToolResponseMessage.ToolResponse>();
    results.add(new ToolResponseMessage.ToolResponse("one", "readFile", "first result"));
    switch (defect) {
      case "orphan" -> results.add(new ToolResponseMessage.ToolResponse("foreign", "readFile", "orphan"));
      case "duplicate" -> {
        results.add(new ToolResponseMessage.ToolResponse("one", "readFile", "duplicate"));
        results.add(new ToolResponseMessage.ToolResponse("two", "readFile", "second result"));
      }
      case "name" -> results.add(new ToolResponseMessage.ToolResponse("two", "writeFile", "wrong name"));
      case "null-name-orphan" -> {
        results.add(new ToolResponseMessage.ToolResponse("foreign", null, "orphan"));
        results.add(new ToolResponseMessage.ToolResponse("two", "readFile", "second result"));
      }
      default -> { }
    }
    List<Message> messages = List.of(call, ToolResponseMessage.builder().responses(results).build(),
        new AssistantMessage("latest answer".repeat(100)));
    assertThat(new CompressionPolicy(estimator).prefixToCompress(messages, 1)).isZero();
  }

  @Test
  void userAndAllResultsStayWithTheirOriginalAssistantInOneCompressionUnit() {
    var call = AssistantMessage.builder().content("commentary").toolCalls(List.of(
        new AssistantMessage.ToolCall("one", "function", "readFile", "{}"),
        new AssistantMessage.ToolCall("two", "function", "readFile", "{}"))).build();
    List<Message> messages = List.of(new UserMessage("old request"), call,
        ToolResponseMessage.builder().responses(List.of(new ToolResponseMessage.ToolResponse("one", "readFile", "first"))).build(),
        ToolResponseMessage.builder().responses(List.of(new ToolResponseMessage.ToolResponse("two", "readFile", "second"))).build(),
        new UserMessage("current request"), new AssistantMessage("latest answer".repeat(100)));
    assertThat(new CompressionPolicy(estimator).prefixToCompress(messages, 1)).isEqualTo(4);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void requestEstimateIncludesReasoningSummaryOpaqueAndWireAttributes(boolean tokenizerAware) {
    var counter = tokenizerAware ? TokenEstimator.tokenizerAware() : estimator;
    var payload = new org.springframework.ai.chat.messages.part.OpaquePayload("fixture", "encrypted_content", "opaque+/=".repeat(1000));
    var reasoning = new org.springframework.ai.chat.messages.part.ReasoningPart("reasoning ".repeat(100), "summary ".repeat(50), payload,
        java.util.Map.of("itemId", "rs_" + "identifier".repeat(100)));
    var message = AssistantMessage.builder().parts(List.of(reasoning,
        new org.springframework.ai.chat.messages.part.TextPart("visible answer", null, java.util.Map.of("phase", "final_answer")))).build();
    long required = counter.message(new AssistantMessage("visible answer"))
        + (long) counter.text(reasoning.text()) + counter.text(reasoning.summary())
        + counter.text(payload.data()) + counter.text(reasoning.attributes().get("itemId"));
    assertThat(counter.message(message)).isGreaterThanOrEqualTo((int) required);
    assertThat(message.getParts()).containsExactly(reasoning, new org.springframework.ai.chat.messages.part.TextPart("visible answer", null, java.util.Map.of("phase", "final_answer")));
    assertThat(reasoning.payload()).isSameAs(payload);
    assertThat(message.getText()).isEqualTo("visible answer");
  }

}
