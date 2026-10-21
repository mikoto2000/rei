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
    assertThat(new CompressionPolicy(estimator).prefixToCompress(messages, 40)).isEqualTo(1);
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
}
